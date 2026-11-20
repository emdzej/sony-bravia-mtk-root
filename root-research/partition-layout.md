# XE9005 (MT5891) eMMC partition layout

Collected on-device 2026-11-20. Sources: `/sys/class/block/mmcblk0pN/start|size` (read via the
root console, `untrusted_app` uid 0 — **not** readable by the adb shell user), `/proc/devices`,
`/proc/mounts`, `/sys/block/dm-N/dm/name`.

- Device: `mmcblk0`, total 30535680 sectors = **14.57 GiB**.
- Sector size 512 B for all numbers below unless said otherwise.
- No GPT by-name symlink dir is exposed (`/dev/block/by-name` absent), GPT header itself not
  readable (block reads SELinux-denied from every context we hold), so pN→name mapping comes
  from inference (mounts, sizes, MTK conventions) unless stated.

## Hardware boot partitions (separate from mmcblk0 user area)

| Node | Size | Role (MTK convention) |
|---|---|---|
| `mmcblk0boot0` | 4096 sec = 2 MiB | MTK preloader / 1st bootloader (part 1) |
| `mmcblk0boot1` | 4096 sec = 2 MiB | MTK preloader / 1st bootloader (part 2, mirror) |
| `mmcblk0rpmb`  | 4096 sec = 2 MiB | RPMB (secure replay-protected region) |

## User-area partitions (63 GPT entries reported by kernel)

| Part | Start (MiB) | Size (MiB) | Notes |
|---|---|---|---|
| p1 | 0.0 | 2.0 | |
| p2 | 2.0 | 2.0 | |
| p3 | 4.0 | 1.0 | |
| p4 | 5.0 | 8.0 | |
| p5 | 13.0 | 20.0 | |
| p6 | 33.0 | 1.0 | |
| p7 | 34.0 | 1.0 | |
| p8 | 35.0 | 1.0 | |
| p9 | 36.0 | 5.0 | |
| p10 | 41.0 | 1.0 | |
| p11 | 42.0 | 4.0 | |
| p12 | 46.0 | 2.0 | |
| p13 | 48.0 | 100.0 | mount-backed image (RO) |
| p14 | 148.0 | 159.0 | |
| p15 | 307.0 | 233.0 | **mounted: `/3rd_rw` (ext4, rw)** |
| p16 | 540.0 | 1.0 | |
| p17 | 541.0 | 124.0 | |
| p18 | 665.0 | 1.0 | |
| p19 | 666.0 | 1.0 | |
| p20 | 667.0 | 1.0 | |
| p21 | 668.0 | 1.0 | |
| p22 | 669.0 | 1.0 | |
| p23 | 670.0 | 38.0 | |
| p24 | 708.0 | 3.0 | |
| p25 | 711.0 | 1.0 | |
| p26 | 712.0 | 1.0 | |
| p27 | 713.0 | 1.0 | |
| p28 | 714.0 | 1.0 | |
| p29 | 715.0 | 3.0 | |
| p30 | 718.0 | 6.0 | |
| p31 | 724.0 | 1.0 | |
| p32 | 725.0 | 1.0 | |
| p33 | 726.0 | 5.0 | |
| p34 | 731.0 | 8.0 | |
| p35 | 739.0 | 2.0 | |
| p36 | 741.0 | 1.0 | |
| p37 | 742.0 | 1.0 | |
| p38 | 743.0 | 2.0 | |
| p39 | 745.0 | 1.0 | |
| p40 | 746.0 | 6.0 | |
| p41 | 752.0 | 2048.0 | **mounted: `/upgrade` (vfat, rw)** — 2 GiB update/store area |
| p42 | 2800.0 | 1.0 | |
| p43 | 2801.0 | 20.0 | |
| p44 | 2821.0 | 1800.0 | |
| p45 | 4621.0 | 8622.0 | |
| p46 | 13243.0 | 600.0 | |
| p47 | 13843.0 | 1.0 | |
| p48 | 13844.0 | 1.0 | |
| p49 | 13845.0 | 1.0 | |
| p50 | 13846.0 | 4.0 | |
| p51 | 13850.0 | 1024.0 | |
| p52 | 14874.0 | 1.0 | |
| p53 | 14875.0 | 1.0 | |
| p54 | 14876.0 | 2.0 | |
| p55 | 14878.0 | 5.0 | |
| p56 | 14883.0 | 1.0 | |
| p57 | 14884.0 | 1.0 | |
| p58 | 14885.0 | 1.0 | |
| p59 | 14886.0 | 1.0 | |
| p60 | 14887.0 | 1.0 | |
| p61 | 14888.0 | 1.0 | |
| p62 | 14889.0 | 1.0 | |
| p63 | 14890.0 | 1.0 | |

Many 1 MiB slots (p6–p8, p10, p16, p18–p22, p25–p28, p31–p32, p36–p37, p39, p42, p47–p49,
p52–p53, p56–p63): small named partitions typical of MTK TV boards (kernel/ recovery/ factory/
EEPROM-backup/ sec-ro etc., names unknown without GPT read).

## Device-mapper and mounts (verified)

| dm | dm/name | Backing | Mounted | FS |
|---|---|---|---|---|
| dm-0 | `system` | (p13 region matches 100 MiB) | `/system` | squashfs ro (dm-verity) |
| dm-1 | `linux_rootfs` | (large RO slot) | `/linux_rootfs` | squashfs ro |
| dm-2 | `3rd` | | `/3rd` | squashfs ro |
| — | — | **p15** | `/3rd_rw` | ext4 rw |
| — | — | **p41** | `/upgrade` | vfat rw |

`/dev/cli` → `/linux_rootfs/dev/cli`, char **241:4**, owner `root:shell`, mode `crw-rw----`,
SELinux label `u:object_r:cli_device:s0`.

## Char device major 241 (`/proc/devices`)

`mtal`, `cli`, `cb`, `gpio` share major 241 (MTK " amusement/common" block). `cli` = minor 4.

## How this was collected

`adb push` a script then run it from the root console (the console's own `sh -c` does not do
`$var` expansion in for-loops — script files are required):

```sh
for p in $(seq 1 63); do
  s=$(cat /sys/class/block/mmcblk0p$p/start  2>/dev/null)
  z=$(cat /sys/class/block/mmcblk0p$p/size   2>/dev/null)
  echo "p$p $s $z"
done
```

(Run from the uid-0 console: `tv-root.sh console` or the python client; raw `cat` of the sysfs
files is not permitted to `u:r:shell:s0`.)
