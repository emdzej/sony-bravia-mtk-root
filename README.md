# sony-bravia-mtk-root

End-to-end security research on a Sony Bravia Android TV (MediaTek MT5891, Android 8.0,
kernel 3.10.79, ARMv7, SELinux patch level 2019-09): **debloat → kernel exploitation →
reproducible one-command RAM-only root → platform mapping → and the documented limits
(bootloader dump / firmware .pkg decryption are blocked without UART)**.

All work was done on hardware the owner controls, purely for interop and customization
(Apple-TV-only dumb-TV use). No production systems.

## Results at a glance

| Goal | Status |
|---|---|
| Debloat to a dumb-TV appliance | ✅ ~250 packages removed, ~580 MB RAM freed |
| Kernel-level root, reproducible | ✅ **uid-0 console via one script**, re-applied after every reboot |
| SELinux bypass | ✖ stays Enforcing — root survives *within* an `untrusted_app` context |
| Persistent root across reboot | ✖ by design (RAM-only page-cache patch; playbook forbids flash writes) |
| Bootloader / eMMC dump | ✖ SELinux blocks every `/dev/block/*` read — needs UART |
| Firmware `.pkg` decryption | ✖ package is fully encrypted; key lives on-device — needs UART |

## The root, in one line

CVE-2022-22706 (MaliGPU `kbase` page-cache write, unpatched on this 2019-09 firmware)
patches **three sites into the `/system/lib/libc.so` page cache in RAM** — a 4-byte ARM
branch at `setresuid`, a Thumb payload in a dead-code cave, and an ARM trampoline — so that
`setresuid(10144,10144,10144)` silently returns 0 without a syscall. A probe app started by
zygote therefore **stays uid 0** and serves a token-authenticated TCP console on port 4445.
A reboot fully reverts everything.

```bash
tools/tv-root.sh apply      # patch libc page cache + start the uid-0 console
tools/tv-root.sh status     # is the hook live / is the console answering?
tools/tv-root.sh console    # interactive uid-0 shell over TCP
tools/tv-root.sh revert     # restore pristine bytes, stop the app
```

The full write-up — primitive construction, the two I-cache/coherency bugs that blocked it
for two sessions, byte-exact ARM/Thumb encodings — is in
[x850d-tool/Sony-XBR-65X850D/FULL-ROOT-STATUS.md](root-research/x850d-tool/Sony-XBR-65X850D/FULL-ROOT-STATUS.md)
("ROOT ACHIEVED" section).

## Repo layout

```
GUIDE.md                     full household-usage guide: adb, debloat, clock, boot animation, root & limits
debloat/                     apply.sh / restore.sh + package tier lists + removed.log
root-research/
  root-playbook.md           safety playbook (read-only-only constraints) + method log
  partition-layout.md        full eMMC partition map (63 partitions) collected via the root console
  pkg-analysis.md            firmware .pkg: fully encrypted end-to-end; tooling attempts + findings
  sources.md                 upstream research sources
  x850d-tool/                COMPLETE toolchain deep-dive (exploit dev, SELinux surface, remap docs)
   tools/tv-root.sh          the one-command root tool (apply/revert/status/console)
   Sony-XBR-65X850D/         per-CVE reports, phase-by-phase exploit sources, vendor tool docs
launcher/                    "M&M Home": the custom dumb-TV home screen (photo background,
                             boot splash, input switcher). Ships a placeholder photo —
                             drop in your own at launcher/app/src/main/res/drawable-nodpi/mm_photo.jpg
```

## Root capabilities & hard limits (SELinux Enforcing)

Works: full process control over zygote-spawned apps, `am`/`pm` as uid 0, sysfs reads
(partition map), page-cache introspection.
Blocked: **all `/dev/block/*` reads** (no bootloader/eMMC dump), property sets, kmsg,
`/data/system` — and `/linux_rootfs` listing.

## Why bootloader dump and `.pkg` decryption don't work from software

- Every `/dev/block/*` read is SELinux-denied from both `u:r:shell:s0` and the uid-0 console
  (`tcontext=u:object_r:block_device:s0`).
- The MediaTek `/dev/cli` debug interface accepts commands (`ioctl`, verified) but has **no
  output path off the device**: `read()` returns EINVAL; Sony's stock `cli_shell` captures
  replies via `/dev/kmsg`, which is denied in every context held; the Sony build ships no
  kernel-log mirror (`logcat -b kernel` stays empty). CLI results reach only the physical UART.
- The OTA `.pkg` (1.7 GB) is **fully encrypted, header included** — zero plaintext runs >21
  chars, no known vendor magics; `epk2extract` (built from source, keys bundled) reports
  `Unsupported input file format`. The decryption key lives on-device (updater/TrustZone).
- Community reports put the key in the **U-Boot boot log during an update** → the remaining
  path for both goals is the UART header, a hardware project (not covered here).

## Safety model

Nothing here flashes a partition, writes to `/dev/block/*`, disables dm-verity, or modifies a
boot/system image. All modification is RAM page-cache only and reverts on reboot — verified.
Default state on the researched device: firmware frozen (no OTAs), SELinux Enforcing.

## Legal / ethics

Owned-hardware research, documented to be reproducible. Details of live credentials are
deliberately absent: the console token ships as a `<YOUR-TOKEN>` placeholder to be set at
build time. Do not apply any of this to hardware you don't own.

## Upstream

The exploit-chain deep-dive under `root-research/x850d-tool/Sony-XBR-65X850D/` is an
adaptation of the [BennyWaitWhat/Sony-XBR-65X850D](https://github.com/BennyWaitWhat/Sony-XBR-65X850D)
research (X850D, MT5890) reworked for this MT5891 target; the porting differences (libc
offsets, I-cache coherency fix) are documented in the logs.

## License

MIT — see [LICENSE](root-research/x850d-tool/Sony-XBR-65X850D/LICENSE).
