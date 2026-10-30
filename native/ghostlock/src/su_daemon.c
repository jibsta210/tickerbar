#define _GNU_SOURCE

#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <grp.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define SOCK_NAME "tickerbar-root"
#define VOLUME_SOCK_NAME "tickerbar-volume-v2"
#define INPUT_SOCK_NAME "tickerbar-input-v3"
#define POWER_SOCK_NAME "tickerbar-power-v1"

static uid_t allowed_uid = (uid_t)-1;

static socklen_t daemon_address(struct sockaddr_un *sun) {
  memset(sun, 0, sizeof(*sun));
  sun->sun_family = AF_UNIX;
  size_t name_len = strlen(SOCK_NAME);
  sun->sun_path[0] = '\0';
  memcpy(sun->sun_path + 1, SOCK_NAME, name_len);
  return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);
}

static socklen_t named_address(struct sockaddr_un *sun, const char *name) {
  memset(sun, 0, sizeof(*sun));
  sun->sun_family = AF_UNIX;
  size_t len = strlen(name);
  sun->sun_path[0] = '\0';
  memcpy(sun->sun_path + 1, name, len);
  return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + len);
}

static void set_root_env(void) {
  setenv("PATH",
         "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:"
         "/apex/com.android.virt/bin:/system_ext/bin:/system/bin:/system/xbin:"
         "/odm/bin:/vendor/bin:/vendor/xbin",
         1);
  setenv("HOME", "/data/local/tmp", 1);
  setenv("USER", "root", 1);
  setenv("LOGNAME", "root", 1);
}

static void xwrite(int fd, const void *buf, size_t len) {
  const char *p = (const char *)buf;
  while (len) {
    ssize_t n = write(fd, p, len);
    if (n < 0 && errno == EINTR) {
      continue;
    }
    if (n <= 0) {
      _exit(111);
    }
    p += n;
    len -= (size_t)n;
  }
}

static int read_full(int fd, void *buf, size_t len) {
  char *p = (char *)buf;
  while (len) {
    ssize_t n = read(fd, p, len);
    if (n < 0 && errno == EINTR) {
      continue;
    }
    if (n <= 0) {
      return 0;
    }
    p += n;
    len -= (size_t)n;
  }
  return 1;
}

static int write_full(int fd, const void *buf, size_t len) {
  const char *p = (const char *)buf;
  while (len) {
    ssize_t n = write(fd, p, len);
    if (n < 0 && errno == EINTR)
      continue;
    if (n <= 0)
      return 0;
    p += n;
    len -= (size_t)n;
  }
  return 1;
}

static int connect_daemon(void) {
  int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (fd < 0) {
    perror("su: socket");
    return -1;
  }

  struct sockaddr_un sun;
  socklen_t sun_len = daemon_address(&sun);

  if (connect(fd, (struct sockaddr *)&sun, sun_len) != 0) {
    perror("su: connect daemon");
    close(fd);
    return -1;
  }
  return fd;
}

static int pump_pair(int a, int b) {
  char buf[4096];
  int a_open = 1;
  int b_open = 1;

  while (a_open || b_open) {
    struct pollfd pfd[2];
    int nfd = 0;
    if (a_open) {
      pfd[nfd].fd = a;
      pfd[nfd].events = POLLIN;
      nfd++;
    }
    if (b_open) {
      pfd[nfd].fd = b;
      pfd[nfd].events = POLLIN;
      nfd++;
    }

    int pr = poll(pfd, (nfds_t)nfd, -1);
    if (pr < 0 && errno == EINTR) {
      continue;
    }
    if (pr < 0) {
      return 1;
    }

    int idx = 0;
    if (a_open) {
      short re = pfd[idx++].revents;
      if (re & POLLIN) {
        ssize_t n = read(a, buf, sizeof(buf));
        if (n > 0) {
          xwrite(b, buf, (size_t)n);
        } else {
          a_open = 0;
          shutdown(b, SHUT_WR);
        }
      } else if (re & (POLLHUP | POLLERR | POLLNVAL)) {
        a_open = 0;
        shutdown(b, SHUT_WR);
      }
    }
    if (b_open) {
      short re = pfd[idx++].revents;
      if (re & POLLIN) {
        ssize_t n = read(b, buf, sizeof(buf));
        if (n > 0) {
          xwrite(a, buf, (size_t)n);
        } else {
          b_open = 0;
          shutdown(a, SHUT_WR);
        }
      } else if (re & (POLLHUP | POLLERR | POLLNVAL)) {
        b_open = 0;
        shutdown(a, SHUT_WR);
      }
    }
  }
  return 0;
}

static int client_main(int argc, char **argv) {
  int fd = connect_daemon();
  if (fd < 0) {
    return 127;
  }

  if (argc >= 3 && strcmp(argv[1], "-c") == 0) {
    char mode = 'C';
    uint32_t len = (uint32_t)strlen(argv[2]);
    xwrite(fd, &mode, 1);
    xwrite(fd, &len, sizeof(len));
    xwrite(fd, argv[2], len);
    shutdown(fd, SHUT_WR);

    char buf[4096];
    for (;;) {
      ssize_t n = read(fd, buf, sizeof(buf));
      if (n < 0 && errno == EINTR) {
        continue;
      }
      if (n <= 0) {
        break;
      }
      xwrite(STDOUT_FILENO, buf, (size_t)n);
    }
    close(fd);
    return 0;
  }

  char mode = 'I';
  xwrite(fd, &mode, 1);
  int rc = pump_pair(STDIN_FILENO, fd);
  close(fd);
  return rc;
}

static int exec_command_client(int conn, const char *cmd) {
  pid_t pid = fork();
  if (pid < 0)
    return 127;
  if (pid == 0) {
    dup2(conn, STDIN_FILENO);
    dup2(conn, STDOUT_FILENO);
    dup2(conn, STDERR_FILENO);
    close(conn);
    set_root_env();
    /* Keep uid 0, but use Android's normal shell SELinux domain so Binder-backed
     * commands can see servicemanager. The exploit starts us in u:r:kernel:s0,
     * where direct file access works but `cmd`/`content` see no services. */
    execl("/system/bin/runcon", "runcon", "u:r:shell:s0",
          "/system/bin/sh", "-c", cmd, (char *)NULL);
    _exit(127);
  }

  int status = 0;
  while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
  }
  if (WIFEXITED(status))
    return WEXITSTATUS(status);
  if (WIFSIGNALED(status))
    return 128 + WTERMSIG(status);
  return 127;
}

static int exec_quiet(const char *cmd) {
  pid_t pid = fork();
  if (pid < 0)
    return 127;
  if (pid == 0) {
    int nullfd = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (nullfd >= 0) {
      dup2(nullfd, STDIN_FILENO);
      dup2(nullfd, STDOUT_FILENO);
      dup2(nullfd, STDERR_FILENO);
      if (nullfd > STDERR_FILENO)
        close(nullfd);
    }
    set_root_env();
    execl("/system/bin/runcon", "runcon", "u:r:shell:s0",
          "/system/bin/sh", "-c", cmd, (char *)NULL);
    _exit(127);
  }
  int status = 0;
  while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
  }
  if (WIFEXITED(status))
    return WEXITSTATUS(status);
  if (WIFSIGNALED(status))
    return 128 + WTERMSIG(status);
  return 127;
}

/* Keep HyperOS's panel disabled only while the app holds this connection. If the
 * accessibility service or app dies, socket EOF immediately restores the stock shade. */
static void serve_statusbar_lease(int conn) {
  static const char disable[] = "cmd statusbar send-disable-flag statusbar-expansion";
  static const char restore[] = "cmd statusbar send-disable-flag none";
  int status = exec_quiet(disable);
  if (!write_full(conn, &status, sizeof(status))) {
    if (status == 0)
      (void)exec_quiet(restore);
    return;
  }
  if (status != 0)
    return;

  char ignored[64];
  while (read(conn, ignored, sizeof(ignored)) > 0) {
  }
  (void)exec_quiet(restore);
}

/* Application overlays are intentionally below SystemUI's status-bar window. While the
 * ticker is visible, hide only the three collapsed-status-bar icon regions so the ticker
 * is the sole content in that band. The live socket is the lease: a crash or disconnect
 * restores SystemUI immediately instead of leaving the phone without status indicators. */
static void serve_statusicon_lease(int conn) {
  static const char disable[] =
      "cmd statusbar send-disable-flag system-icons clock notification-icons";
  static const char restore[] = "cmd statusbar send-disable-flag none";
  int status = exec_quiet(disable);
  xwrite(conn, &status, sizeof(status));
  if (status != 0)
    return;
  char sink[32];
  while (read(conn, sink, sizeof(sink)) > 0) {
  }
  (void)exec_quiet(restore);
}

static int open_pty_master(char *slave, size_t slave_len) {
  int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
  if (master < 0) {
    return -1;
  }
  if (grantpt(master) != 0 || unlockpt(master) != 0) {
    close(master);
    return -1;
  }
  if (ptsname_r(master, slave, slave_len) != 0) {
    close(master);
    return -1;
  }
  return master;
}

static void exec_interactive_client(int conn) {
  char slave_name[128];
  int master = open_pty_master(slave_name, sizeof(slave_name));
  if (master < 0) {
    const char msg[] = "su daemon: failed to open pty\n";
    xwrite(conn, msg, sizeof(msg) - 1);
    return;
  }

  pid_t pid = fork();
  if (pid == 0) {
    setsid();
    int slave = open(slave_name, O_RDWR | O_NOCTTY);
    if (slave < 0) {
      _exit(126);
    }
    ioctl(slave, TIOCSCTTY, 0);
    dup2(slave, STDIN_FILENO);
    dup2(slave, STDOUT_FILENO);
    dup2(slave, STDERR_FILENO);
    if (slave > STDERR_FILENO) {
      close(slave);
    }
    close(master);
    close(conn);
    set_root_env();
    execl("/system/bin/runcon", "runcon", "u:r:shell:s0",
          "/system/bin/sh", "-i", (char *)NULL);
    _exit(127);
  }

  pump_pair(conn, master);
  kill(pid, SIGHUP);
  int status = 0;
  while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
  }
  close(master);
}

static void serve_one(int conn) {
  struct ucred peer;
  socklen_t peer_len = sizeof(peer);
  if (getsockopt(conn, SOL_SOCKET, SO_PEERCRED, &peer, &peer_len) != 0 ||
      (peer.uid != 0 && peer.uid != allowed_uid)) {
    const char denied[] = "tickerbar root: peer denied\n";
    (void)write(conn, denied, sizeof(denied) - 1);
    return;
  }

  char mode = 0;
  if (!read_full(conn, &mode, 1)) {
    return;
  }

  if (mode == 'C' || mode == 'R') {
    uint32_t len = 0;
    if (!read_full(conn, &len, sizeof(len)) || len > 65536) {
      return;
    }
    char *cmd = calloc(1, (size_t)len + 1);
    if (!cmd) {
      return;
    }
    if (!read_full(conn, cmd, len)) {
      free(cmd);
      return;
    }
    int status = exec_command_client(conn, cmd);
    free(cmd);
    if (mode == 'R')
      xwrite(conn, &status, sizeof(status));
  } else if (mode == 'I') {
    exec_interactive_client(conn);
  } else if (mode == 'S') {
    serve_statusbar_lease(conn);
  } else if (mode == 'T') {
    serve_statusicon_lease(conn);
  } else if (mode == 'X' && peer.uid == allowed_uid) {
    /* The app can explicitly end the temporary privileged session.  Keep this
     * out of the shell/ADB allowance: only the UID the exploit authenticated
     * when it created the daemon may shut it down through the protocol. */
    int status = 0;
    xwrite(conn, &status, sizeof(status));
    pid_t parent = getppid();
    if (parent > 1)
      (void)kill(parent, SIGTERM);
  }
}

static int daemon_main(void) {
  signal(SIGPIPE, SIG_IGN);
  set_root_env();

  int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (fd < 0) {
    perror("socket");
    return 1;
  }

  struct sockaddr_un sun;
  socklen_t sun_len = daemon_address(&sun);

  if (bind(fd, (struct sockaddr *)&sun, sun_len) != 0) {
    perror("bind");
    return 1;
  }
  if (listen(fd, 16) != 0) {
    perror("listen");
    return 1;
  }

  for (;;) {
    int conn = accept4(fd, NULL, NULL, SOCK_CLOEXEC);
    if (conn < 0 && errno == EINTR) {
      continue;
    }
    if (conn < 0) {
      perror("accept");
      sleep(1);
      continue;
    }

    pid_t pid = fork();
    if (pid == 0) {
      close(fd);
      serve_one(conn);
      close(conn);
      _exit(0);
    }
    close(conn);
    while (waitpid(-1, NULL, WNOHANG) > 0) {
    }
  }
}

/* -------------------------------------------------------------------------
 * Physical volume-key lease.
 *
 * Accessibility was previously used only because Android gives ordinary apps
 * no global key filter. The root helper grabs the two physical key devices,
 * asks TickerBar whether each event is consumed, and re-injects untouched
 * events through a dedicated uinput device. Closing the socket releases both
 * EVIOCGRAB leases immediately, so a dead/restarted app restores stock keys.
 */

struct volume_packet {
  int32_t code;
  int32_t value;
};

static int key_bit(const unsigned long *bits, unsigned int key) {
  const unsigned int per = 8U * (unsigned int)sizeof(unsigned long);
  return (bits[key / per] >> (key % per)) & 1UL;
}

static int open_volume_devices(int fds[4], unsigned long *all_keys,
                               size_t key_words) {
  int count = 0;
  DIR *dir = opendir("/dev/input");
  if (!dir)
    return 0;
  struct dirent *entry;
  while ((entry = readdir(dir)) && count < 4) {
    if (strncmp(entry->d_name, "event", 5) != 0)
      continue;
    char path[128];
    snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0)
      continue;
    char name[128] = {0};
    (void)ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name);
    if (strstr(name, "TickerBar") || strstr(name, "uinput")) {
      close(fd);
      continue;
    }
    unsigned long bits[(KEY_MAX + 8U * sizeof(unsigned long)) /
                       (8U * sizeof(unsigned long))];
    memset(bits, 0, sizeof(bits));
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(bits)), bits) < 0 ||
        (!key_bit(bits, KEY_VOLUMEUP) && !key_bit(bits, KEY_VOLUMEDOWN))) {
      close(fd);
      continue;
    }
    int grab = 1;
    if (ioctl(fd, EVIOCGRAB, grab) != 0) {
      close(fd);
      continue;
    }
    for (size_t i = 0; i < key_words && i < sizeof(bits) / sizeof(bits[0]); ++i)
      all_keys[i] |= bits[i];
    fds[count++] = fd;
  }
  closedir(dir);
  return count;
}

static int open_volume_uinput(const unsigned long *keys, size_t key_words) {
  int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
  if (fd < 0)
    return -1;
  if (ioctl(fd, UI_SET_EVBIT, EV_KEY) != 0 ||
      ioctl(fd, UI_SET_EVBIT, EV_SYN) != 0) {
    close(fd);
    return -1;
  }
  for (unsigned int key = 0; key <= KEY_MAX; ++key) {
    unsigned int word = key / (8U * (unsigned int)sizeof(unsigned long));
    if (word < key_words && key_bit(keys, key) &&
        ioctl(fd, UI_SET_KEYBIT, key) != 0) {
      close(fd);
      return -1;
    }
  }
  struct uinput_setup setup;
  memset(&setup, 0, sizeof(setup));
  setup.id.bustype = BUS_VIRTUAL;
  setup.id.vendor = 0x5442;
  setup.id.product = 1;
  snprintf(setup.name, sizeof(setup.name), "TickerBar volume passthrough");
  if (ioctl(fd, UI_DEV_SETUP, &setup) != 0 || ioctl(fd, UI_DEV_CREATE) != 0) {
    close(fd);
    return -1;
  }
  usleep(100000);
  return fd;
}

static void inject_volume_event(int fd, int code, int value) {
  if (fd < 0)
    return;
  struct input_event events[2];
  memset(events, 0, sizeof(events));
  events[0].type = EV_KEY;
  events[0].code = (uint16_t)code;
  events[0].value = value;
  events[1].type = EV_SYN;
  events[1].code = SYN_REPORT;
  (void)write(fd, events, sizeof(events));
}

static void serve_volume_client(int conn) {
  int inputs[4] = {-1, -1, -1, -1};
  unsigned long keys[(KEY_MAX + 8U * sizeof(unsigned long)) /
                     (8U * sizeof(unsigned long))];
  memset(keys, 0, sizeof(keys));
  int count = open_volume_devices(inputs, keys, sizeof(keys) / sizeof(keys[0]));
  int uinput = open_volume_uinput(keys, sizeof(keys) / sizeof(keys[0]));
  int status = count > 0 && uinput >= 0 ? 0 : 1;
  if (!write_full(conn, &status, sizeof(status)) || status != 0)
    goto done;

  for (;;) {
    struct pollfd polls[5];
    polls[0].fd = conn;
    polls[0].events = POLLIN | POLLHUP | POLLERR;
    for (int i = 0; i < count; ++i) {
      polls[i + 1].fd = inputs[i];
      polls[i + 1].events = POLLIN | POLLHUP | POLLERR;
    }
    int ready = poll(polls, (nfds_t)(count + 1), -1);
    if (ready < 0 && errno == EINTR)
      continue;
    if (ready < 0 || (polls[0].revents & (POLLHUP | POLLERR | POLLNVAL)))
      break;
    if (polls[0].revents & POLLIN) {
      char ignored[32];
      if (read(conn, ignored, sizeof(ignored)) <= 0)
        break;
    }
    for (int i = 0; i < count; ++i) {
      if (!(polls[i + 1].revents & POLLIN))
        continue;
      struct input_event event;
      while (read(inputs[i], &event, sizeof(event)) == sizeof(event)) {
        if (event.type != EV_KEY)
          continue;
        if (event.code != KEY_VOLUMEUP && event.code != KEY_VOLUMEDOWN) {
          /* EVIOCGRAB applies to the whole device. Preserve unrelated buttons
           * (some boards put power/camera beside volume on the same node). */
          inject_volume_event(uinput, event.code, event.value);
          continue;
        }
        struct volume_packet packet = {(int32_t)event.code, event.value};
        if (!write_full(conn, &packet, sizeof(packet)))
          goto done;
        struct pollfd response_poll = {conn, POLLIN | POLLHUP | POLLERR, 0};
        char response = 'P';
        int response_ready;
        do { response_ready = poll(&response_poll, 1, 750); }
        while (response_ready < 0 && errno == EINTR);
        if (response_ready <= 0 || !(response_poll.revents & POLLIN)) {
          inject_volume_event(uinput, event.code, event.value);
          goto done;
        }
        if (read(conn, &response, 1) != 1)
          goto done;
        if (response != 'C')
          inject_volume_event(uinput, event.code, event.value);
      }
    }
  }

done:
  for (int i = 0; i < count; ++i) {
    if (inputs[i] >= 0) {
      int release = 0;
      (void)ioctl(inputs[i], EVIOCGRAB, release);
      close(inputs[i]);
    }
  }
  if (uinput >= 0) {
    (void)ioctl(uinput, UI_DEV_DESTROY);
    close(uinput);
  }
}

static int volume_helper_main(uid_t app_uid) {
  if (geteuid() != 0 || app_uid < 10000)
    return 126;
  (void)prctl(PR_SET_NAME, "tickerbar-vol2", 0, 0, 0);
  signal(SIGPIPE, SIG_IGN);
  int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (fd < 0)
    return 1;
  struct sockaddr_un sun;
  socklen_t sun_len = named_address(&sun, VOLUME_SOCK_NAME);
  if (bind(fd, (struct sockaddr *)&sun, sun_len) != 0 || listen(fd, 2) != 0) {
    close(fd);
    return 1;
  }
  for (;;) {
    int conn = accept4(fd, NULL, NULL, SOCK_CLOEXEC);
    if (conn < 0 && errno == EINTR)
      continue;
    if (conn < 0)
      break;
    struct ucred peer;
    socklen_t peer_len = sizeof(peer);
    if (getsockopt(conn, SOL_SOCKET, SO_PEERCRED, &peer, &peer_len) == 0 &&
        (peer.uid == app_uid || peer.uid == 0))
      serve_volume_client(conn);
    close(conn);
  }
  close(fd);
  return 1;
}

/* -------------------------------------------------------------------------
 * Passive touchscreen gestures.
 *
 * Unlike the retired volume helper, this never EVIOCGRABs an input device and
 * never injects events. Android and every app receive the original touch stream
 * unchanged. TickerBar merely observes taps/swipes on the two inert custom keys
 * rendered by SystemUI's stock navigation-bar inflater.
 */

struct input_config {
  int32_t screen_w, screen_h, nav_h;
  int32_t wallet_on, wallet_pos, wallet_trigger, wallet_dist;
  int32_t corner_on, corner_side, corner_trigger, corner_hold;
  int32_t corner_width, slop;
  int32_t ticker_on, ticker_height;
};

static int64_t monotonic_ms(void) {
  struct timespec now;
  if (clock_gettime(CLOCK_MONOTONIC, &now) != 0)
    return 0;
  return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static int open_touchscreen(int *max_x, int *max_y) {
  DIR *dir = opendir("/dev/input");
  if (!dir)
    return -1;
  int result = -1;
  struct dirent *entry;
  while ((entry = readdir(dir))) {
    if (strncmp(entry->d_name, "event", 5) != 0)
      continue;
    char path[128];
    snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0)
      continue;
    char name[128] = {0};
    (void)ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name);
    if (strstr(name, "TickerBar") || strstr(name, "uinput")) {
      close(fd);
      continue;
    }
    unsigned long abs_bits[(ABS_MAX + 8U * sizeof(unsigned long)) /
                           (8U * sizeof(unsigned long))];
    memset(abs_bits, 0, sizeof(abs_bits));
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) < 0 ||
        !key_bit(abs_bits, ABS_MT_POSITION_X) ||
        !key_bit(abs_bits, ABS_MT_POSITION_Y)) {
      close(fd);
      continue;
    }
    struct input_absinfo xinfo, yinfo;
    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &xinfo) != 0 ||
        ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &yinfo) != 0) {
      close(fd);
      continue;
    }
    *max_x = xinfo.maximum > xinfo.minimum ? xinfo.maximum - xinfo.minimum : 1;
    *max_y = yinfo.maximum > yinfo.minimum ? yinfo.maximum - yinfo.minimum : 1;
    result = fd;
    break;
  }
  closedir(dir);
  return result;
}

/* A second read-only evdev client does not consume or alter Android's key stream. It only
 * lets the app notice Volume Up early enough to clear a manually-entered DND state while
 * the original event continues to AudioService normally. */
static int open_volume_observer(void) {
  DIR *dir = opendir("/dev/input");
  if (!dir)
    return -1;
  int result = -1;
  struct dirent *entry;
  while ((entry = readdir(dir))) {
    if (strncmp(entry->d_name, "event", 5) != 0)
      continue;
    char path[128];
    snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0)
      continue;
    char name[128] = {0};
    (void)ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name);
    if (strstr(name, "TickerBar") || strstr(name, "uinput")) {
      close(fd);
      continue;
    }
    unsigned long bits[(KEY_MAX + 8U * sizeof(unsigned long)) /
                       (8U * sizeof(unsigned long))];
    memset(bits, 0, sizeof(bits));
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(bits)), bits) >= 0 &&
        key_bit(bits, KEY_VOLUMEUP)) {
      result = fd;
      break;
    }
    close(fd);
  }
  closedir(dir);
  return result;
}

static int in_corner(const struct input_config *c, int x, int y) {
  int width = c->corner_width > 0 ? c->corner_width : c->screen_w * 15 / 100;
  if (width < c->screen_w / 20)
    width = c->screen_w / 20;
  if (y < c->screen_h - c->nav_h)
    return 0;
  return c->corner_side == 0 ? x <= width : x >= c->screen_w - width;
}

static int in_wallet(const struct input_config *c, int x, int y) {
  if (y < c->screen_h - c->nav_h)
    return 0;
  int width = c->screen_w / 5;
  int center = c->wallet_pos == 0 ? width / 2
             : c->wallet_pos == 2 ? c->screen_w - width / 2
             : width / 2; /* The native nav layout keeps Wallet at the left edge. */
  return x >= center - width / 2 && x <= center + width / 2;
}

static int in_ticker(const struct input_config *c, int x, int y) {
  (void)x;
  int height = c->ticker_height > 0 ? c->ticker_height : c->screen_h / 20;
  return c->ticker_on && y >= 0 && y <= height;
}

static void serve_input_client(int conn) {
  int max_x = 1, max_y = 1;
  int input = open_touchscreen(&max_x, &max_y);
  int volume = open_volume_observer();
  int status = input >= 0 ? 0 : 1;
  if (!write_full(conn, &status, sizeof(status)) || status != 0)
    goto done;

  struct input_config config;
  memset(&config, 0, sizeof(config));
  if (!read_full(conn, &config, sizeof(config)))
    goto done;

  int raw_x = 0, raw_y = 0, x = 0, y = 0;
  int down_x = 0, down_y = 0, max_dx = 0, max_dy = 0;
  int active = 0, pending_down = 0, pending_up = 0, moved = 0, fired = 0;
  int64_t down_at = 0, first_tap_at = 0;
  int first_x = 0, first_y = 0;

  for (;;) {
    int timeout = -1;
    if (active && !moved && !fired && config.corner_on &&
        config.corner_trigger == 0 && in_corner(&config, down_x, down_y)) {
      int hold = config.corner_hold < 150 ? 150 : config.corner_hold;
      int64_t remaining = down_at + hold - monotonic_ms();
      timeout = remaining <= 0 ? 0 : remaining > 2000 ? 2000 : (int)remaining;
    }
    struct pollfd polls[3] = {
      {conn, POLLIN | POLLHUP | POLLERR, 0},
      {input, POLLIN | POLLHUP | POLLERR, 0},
      {volume, volume >= 0 ? POLLIN | POLLHUP | POLLERR : 0, 0}
    };
    int ready;
    do { ready = poll(polls, 3, timeout); } while (ready < 0 && errno == EINTR);
    if (ready < 0 || (polls[0].revents & (POLLHUP | POLLERR | POLLNVAL)))
      break;
    if (ready == 0) {
      if (active && !moved && !fired && in_corner(&config, down_x, down_y)) {
        char event = 'C';
        if (!write_full(conn, &event, 1)) break;
        fired = 1;
      }
      continue;
    }
    if (polls[0].revents & POLLIN) {
      if (!read_full(conn, &config, sizeof(config)))
        break;
    }
    if (volume >= 0 && (polls[2].revents & POLLIN)) {
      struct input_event key_event;
      while (read(volume, &key_event, sizeof(key_event)) == sizeof(key_event)) {
        if (key_event.type == EV_KEY && key_event.code == KEY_VOLUMEUP && key_event.value == 1) {
          char out = 'U';
          if (!write_full(conn, &out, 1)) goto done;
        }
      }
    }
    if (!(polls[1].revents & POLLIN))
      continue;
    struct input_event event;
    while (read(input, &event, sizeof(event)) == sizeof(event)) {
      if (event.type == EV_ABS && event.code == ABS_MT_POSITION_X)
        raw_x = event.value;
      else if (event.type == EV_ABS && event.code == ABS_MT_POSITION_Y)
        raw_y = event.value;
      else if (event.type == EV_ABS && event.code == ABS_MT_TRACKING_ID) {
        if (event.value >= 0) { active = 1; pending_down = 1; pending_up = 0; }
        else if (active) pending_up = 1;
      } else if (event.type == EV_SYN && event.code == SYN_REPORT) {
        x = raw_x * config.screen_w / max_x;
        y = raw_y * config.screen_h / max_y;
        if (pending_down) {
          pending_down = 0; down_x = x; down_y = y; down_at = monotonic_ms();
          max_dx = max_dy = 0;
          moved = fired = 0;
        } else if (active && !pending_up) {
          int slop = config.slop > 0 ? config.slop : 30;
          int dx = abs(x - down_x), dy = abs(y - down_y);
          if (dx > max_dx) max_dx = dx;
          if (dy > max_dy) max_dy = dy;
          if (abs(x - down_x) > slop || abs(y - down_y) > slop) moved = 1;
        }
        if (pending_up) {
          int64_t now = monotonic_ms();
          int slop = config.slop > 0 ? config.slop : 30;
          int tap = !moved && now - down_at < 500;
          if (!fired && in_ticker(&config, down_x, down_y)) {
            /* A status-bar tap has little leverage and naturally drifts more than Android's
             * normal click slop. Keep real pulls/flicks distinct, but don't turn a 1 cm
             * thumb tap into a random dismiss. */
            int ticker_tap = now - down_at < 700
                && max_dx <= slop * 2 && max_dy <= slop * 2;
            char out = ticker_tap ? 'T' : y - down_y > slop ? 'N' : 'D';
            if (!write_full(conn, &out, 1)) goto done;
            fired = 1;
          }
          if (!fired && config.corner_on && config.corner_trigger == 1 && tap &&
              in_corner(&config, down_x, down_y)) {
            if (first_tap_at && now - first_tap_at <= 400 &&
                abs(down_x - first_x) < slop * 4 && abs(down_y - first_y) < slop * 4) {
              char out = 'C';
              if (!write_full(conn, &out, 1)) goto done;
              first_tap_at = 0;
            } else {
              first_tap_at = now; first_x = down_x; first_y = down_y;
            }
          }
          if (!fired && config.wallet_on && in_wallet(&config, down_x, down_y) &&
              !(config.corner_on && in_corner(&config, down_x, down_y))) {
            int swipe = down_y - y >= (config.wallet_dist > 0 ? config.wallet_dist : 40);
            int wanted = (config.wallet_trigger == 0 && (tap || swipe)) ||
                         (config.wallet_trigger == 1 && tap) ||
                         (config.wallet_trigger == 2 && swipe);
            if (wanted) {
              char out = 'W';
              if (!write_full(conn, &out, 1)) goto done;
            }
          }
          active = pending_up = 0;
        }
      }
    }
  }

done:
  if (input >= 0) close(input);
  if (volume >= 0) close(volume);
}

static int input_helper_main(uid_t app_uid) {
  if (geteuid() != 0 || app_uid < 10000)
    return 126;
  (void)prctl(PR_SET_NAME, "tb-input3", 0, 0, 0);
  signal(SIGPIPE, SIG_IGN);
  int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (fd < 0)
    return 1;
  struct sockaddr_un sun;
  socklen_t sun_len = named_address(&sun, INPUT_SOCK_NAME);
  if (bind(fd, (struct sockaddr *)&sun, sun_len) != 0 || listen(fd, 2) != 0) {
    close(fd);
    return 1;
  }
  for (;;) {
    int conn = accept4(fd, NULL, NULL, SOCK_CLOEXEC);
    if (conn < 0 && errno == EINTR) continue;
    if (conn < 0) break;
    struct ucred peer;
    socklen_t peer_len = sizeof(peer);
    if (getsockopt(conn, SOL_SOCKET, SO_PEERCRED, &peer, &peer_len) == 0 &&
        (peer.uid == app_uid || peer.uid == 0))
      serve_input_client(conn);
    close(conn);
  }
  close(fd);
  return 1;
}

/* -------------------------------------------------------------------------
 * Passive power-button observer.
 *
 * This deliberately never calls EVIOCGRAB and never injects a key. HyperOS
 * remains the sole owner of single press, long press, power menu and SOS. The
 * authenticated app client merely receives physical KEY_POWER down events.
 */

static int open_power_device(void) {
  DIR *dir = opendir("/dev/input");
  if (!dir)
    return -1;
  int best = -1;
  int best_score = -1;
  struct dirent *entry;
  while ((entry = readdir(dir))) {
    if (strncmp(entry->d_name, "event", 5) != 0)
      continue;
    char path[128];
    snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0)
      continue;
    char name[128] = {0};
    (void)ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name);
    unsigned long bits[(KEY_MAX + 8U * sizeof(unsigned long)) /
                       (8U * sizeof(unsigned long))];
    memset(bits, 0, sizeof(bits));
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(bits)), bits) < 0 ||
        !key_bit(bits, KEY_POWER) || strstr(name, "TickerBar") ||
        strstr(name, "uinput") || strstr(name, "bark") ||
        strstr(name, "touch") || strstr(name, "fts")) {
      close(fd);
      continue;
    }
    int score = strcmp(name, "pmic_pwrkey") == 0 ? 100
              : strstr(name, "pwrkey") ? 50 : 1;
    if (score > best_score) {
      if (best >= 0) close(best);
      best = fd;
      best_score = score;
    } else close(fd);
  }
  closedir(dir);
  return best;
}

static void serve_power_client(int conn) {
  int input = open_power_device();
  int status = input >= 0 ? 0 : 1;
  if (!write_full(conn, &status, sizeof(status)) || status != 0)
    goto done;
  for (;;) {
    struct pollfd polls[2] = {
      {conn, POLLIN | POLLHUP | POLLERR, 0},
      {input, POLLIN | POLLHUP | POLLERR, 0}
    };
    int ready;
    do { ready = poll(polls, 2, -1); } while (ready < 0 && errno == EINTR);
    if (ready < 0 || (polls[0].revents & (POLLHUP | POLLERR | POLLNVAL)))
      break;
    if (polls[0].revents & POLLIN) {
      char ignored[16];
      if (read(conn, ignored, sizeof(ignored)) <= 0) break;
    }
    if (!(polls[1].revents & POLLIN))
      continue;
    struct input_event event;
    while (read(input, &event, sizeof(event)) == sizeof(event)) {
      if (event.type == EV_KEY && event.code == KEY_POWER && event.value == 1) {
        char out = 'P';
        if (!write_full(conn, &out, 1)) goto done;
      }
    }
  }
done:
  if (input >= 0) close(input);
}

static int power_helper_main(uid_t app_uid) {
  if (geteuid() != 0 || app_uid < 10000)
    return 126;
  (void)prctl(PR_SET_NAME, "tb-power", 0, 0, 0);
  signal(SIGPIPE, SIG_IGN);
  int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (fd < 0)
    return 1;
  struct sockaddr_un sun;
  socklen_t sun_len = named_address(&sun, POWER_SOCK_NAME);
  if (bind(fd, (struct sockaddr *)&sun, sun_len) != 0 || listen(fd, 2) != 0) {
    close(fd);
    return 1;
  }
  for (;;) {
    int conn = accept4(fd, NULL, NULL, SOCK_CLOEXEC);
    if (conn < 0 && errno == EINTR) continue;
    if (conn < 0) break;
    struct ucred peer;
    socklen_t peer_len = sizeof(peer);
    if (getsockopt(conn, SOL_SOCKET, SO_PEERCRED, &peer, &peer_len) == 0 &&
        (peer.uid == app_uid || peer.uid == 0))
      serve_power_client(conn);
    close(conn);
  }
  close(fd);
  return 1;
}

static int umh_main(void) {
  if (geteuid() != 0) {
    return 126;
  }
  if (setresgid(0, 0, 0) != 0 || setresuid(0, 0, 0) != 0 ||
      getuid() != 0 || geteuid() != 0 || getgid() != 0 || getegid() != 0) {
    return 125;
  }

  return daemon_main();
}

int tickerbar_su_main(int argc, char **argv) {
  const char *uid_env = getenv("TICKERBAR_ALLOWED_UID");
  if (uid_env && *uid_env) {
    char *end = NULL;
    errno = 0;
    unsigned long parsed = strtoul(uid_env, &end, 10);
    if (!errno && end != uid_env && *end == '\0' && parsed <= UINT32_MAX)
      allowed_uid = (uid_t)parsed;
  }
  if (argc >= 2 && strcmp(argv[1], "--daemon") == 0) {
    return daemon_main();
  }
  if (argc >= 2 && strcmp(argv[1], "--umh") == 0) {
    return umh_main();
  }
  if (argc >= 3 && strcmp(argv[1], "--volume-helper") == 0) {
    char *end = NULL;
    unsigned long uid = strtoul(argv[2], &end, 10);
    if (!end || *end != '\0' || uid > UINT32_MAX)
      return 2;
    return volume_helper_main((uid_t)uid);
  }
  if (argc >= 3 && strcmp(argv[1], "--input-helper") == 0) {
    char *end = NULL;
    unsigned long uid = strtoul(argv[2], &end, 10);
    if (!end || *end != '\0' || uid > UINT32_MAX)
      return 2;
    return input_helper_main((uid_t)uid);
  }
  if (argc >= 3 && strcmp(argv[1], "--power-helper") == 0) {
    char *end = NULL;
    unsigned long uid = strtoul(argv[2], &end, 10);
    if (!end || *end != '\0' || uid > UINT32_MAX)
      return 2;
    return power_helper_main((uid_t)uid);
  }
  return client_main(argc, argv);
}

int tickerbar_start_daemon(uid_t app_uid) {
  allowed_uid = app_uid;
  return daemon_main();
}

int tickerbar_daemon_available(void) {
  int fd = connect_daemon();
  if (fd < 0)
    return 0;
  close(fd);
  return 1;
}

#ifdef SU_DAEMON_STANDALONE
int main(int argc, char **argv) {
  return tickerbar_su_main(argc, argv);
}
#endif
