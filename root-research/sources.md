# Root research sources (collected 2026-11-18)

Device: Sony KD-55XE9005, MT5891, Android 8.0 OPR2.170623.027.S30, patch 2019-09-01,
kernel 3.10.79, 32-bit armv7, bootloader locked, SELinux enforcing. See ../GUIDE.md §7a.

## MediaTek TV / Sony Bravia root threads
- https://xdaforums.com/t/rooting-mediatek-based-linux-smart-tv.3150281/
- https://xdaforums.com/t/sony-android-tv-root.3572345/
- https://xdaforums.com/t/sony-android-tv-users.3096901/
- https://forum.kodi.tv/showthread.php?tid=229348
- https://github.com/bkerler/mtkclient/discussions/185  (Bravia feasibility discussion; unverified)

## Kernel LPE references (none with a public 32-bit/MT5891 exploit)
- CVE-2019-2215 Bad Binder RCA: https://googleprojectzero.github.io/0days-in-the-wild/0day-RCAs/2019/CVE-2019-2215.html
- Bad Binder on TVs attempt: https://xdaforums.com/t/root-with-cve-2019-2215.3979341/
- CVE-2020-0069 mtk-su (Quarkslab): https://blog.quarkslab.com/cve-2020-0069-autopsy-of-the-most-stable-mediatek-rootkit.html
- mtk-su overview: https://www.xda-developers.com/mediatek-su-rootkit-exploit/
- CVE-2020-0041 (Synacktiv): https://synacktiv.com/publications/binder-analysis-and-exploitation-of-cve-2020-0041
- Automated root tooling (phones, 64-bit): https://github.com/R0rt1z2/AutomatedRoot

## Hardware / firmware
- U-Boot hacking (Synacktiv): https://www.synacktiv.com/en/publications/i-hack-u-boot
- Sony mmap advisory: https://www.sony.ie/electronics/support/articles/00171297
- Sony hidden service menu overview: https://www.slashgear.com/2004525/sony-tv-has-hidden-menu-how-to-access-it

## On-device facts worth keeping
- Firmware = AES-encrypted signed .pkg; unpackable (epk2extract) but NOT re-signable publicly → main blocker.
- /system + /linux_rootfs + /3rd are read-only squashfs via device-mapper (dm-verity).
- MediaTek debug nodes present: /dev/cli (cli_device, root:shell 0660), /system/bin/cli + cli_shell.
  Not weaponized — noted only as the MediaTek CLI debug interface.
- Keep firmware FROZEN: updates raise the patch level and close the only old-kernel holes.

## BEST SOFTWARE LEAD (2026-11-18): Mali GPU, sibling chip
- https://github.com/BennyWaitWhat/Sony-XBR-65X850D
  Sony XBR-65X850D, MT5890 (sibling of our MT5891), Android 8, patch 2020-01.
  CVE-2022-22706 in ARM Mali kbase driver r20p0 -> persistent uid-0.
  Source only (NDK/SDK/JDK17 to build); tied to MT5890 offsets/driver version; no MT5891 mention.
  Removal trivial (RAM-only hook + scheduled re-apply; uninstall+reboot restores clean libc). Low brick risk.
  Our TV HAS the component: /dev/mali0 + mali_kbase present at patch 2019-09.
  Gap = porting offsets MT5890->MT5891 = exploit dev = out of scope for this project.
- Mali kbase CVE background (ARM / GHSL Man Yue Mo writeups) — CVE-2022-22706, CVE-2022-20186, CVE-2023-4211.

## tpm171e (2026-11-19) — primary platform-specific root reference
- https://github.com/yath/tpm171e  Philips MT5891 (same SoC as our Sony). Unprivileged adb -> /dev/cli
  kernel mem r/w -> writes getroot.elf at CmdVersion handler addr -> root. RAM-only, low brick risk.
  NO Sony port (searched). Gap = Sony-specific CmdVersion/module addresses = porting = out of scope.
  AVOID its U-Boot/partition route (recoveryB / reboot bootloader) = brick risk.
- Verified on-device: /dev/cli present, openable r/w from shell user (uid 2000, group shell).
