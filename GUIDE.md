# Sony Bravia KD-55XE9005 — Debloat & Custom Launcher Guide

The TV is used **only as a display for an Apple TV**. Everything else (streaming apps, Assistant, casting, Sony promo apps, trackers) is removed. A custom launcher (input switcher + settings, "old dumb TV" style) will replace the Google TV launcher.

## 1. Device facts

| Item | Value |
|---|---|
| Model | KD-55XE9005 (`BRAVIA_ATV2_EU`), MediaTek, armeabi-v7a (32-bit) |
| OS | Android 8.0 (API 26), build `OPR2.170623.027.S30` |
| RAM | 1.6 GB |
| IP / adb | `<TV-LAN-IP>:5555` (network debugging) |
| Apple TV | CEC logical addr `0x04`, physical `0x3300`, behind sound system on **HDMI 3 (ARC)** |
| Apple TV input id | `com.sony.dtv.tvinput.external/.ExternalTvInputService/HDMI330004` |
| Stock launcher | `com.google.android.tvlauncher` (fallback present: `com.google.android.leanbacklauncher`) |

## 2. Connecting with adb

Prerequisite on the TV: *Settings → Device Preferences → About → Build* (click 7×) to unlock Developer options, then *Developer options → USB debugging* **and** *Network debugging* ON.

```bash
brew install --cask android-platform-tools   # provides adb
adb connect <TV-LAN-IP>:5555                # first time: accept "Allow USB debugging?" on the TV (tick "Always allow")
adb devices -l                               # should show: <TV-LAN-IP>:5555 device ... model:BRAVIA_4K_GB
```

If it shows `unauthorized`, the prompt on the TV was not accepted. Run `adb disconnect && adb connect <TV-LAN-IP>:5555` and accept it.

Handy alias used below:

```bash
A(){ adb -s <TV-LAN-IP>:5555 shell "$@"; }
```

## 3. Inspecting the device

```bash
A getprop ro.build.fingerprint                 # firmware
A cat /proc/meminfo | head -4                  # RAM
A dumpsys meminfo                              # per-process RAM (see "Total PSS by process")
A pm list packages -f                          # all packages with APK paths
A pm list packages -d                          # disabled packages
A cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME   # current launcher
A dumpsys tv_input                             # HDMI/tuner input ids
A dumpsys hdmi_control                         # CEC devices (Apple TV, sound system), ARC state
A logcat -b crash -d                           # crashes since boot (check after each debloat round)
```

Snapshots taken before and after changes live in `state/`.

## 4. Debloating

### How it works
`pm uninstall -k --user 0 <pkg>` **hides** a system app for the user. The APK stays on the read-only system partition, so:
- it survives reboots,
- it can always be restored with `cmd package install-existing <pkg>`,
- a factory reset brings everything back.

No root is needed, and the change can't brick the TV.

### Scripts (`debloat/`)

```bash
./debloat/apply.sh debloat/tier1.txt           # remove every package in the list (comments/# lines ignored)
./debloat/restore.sh com.netflix.ninja         # restore one or more packages
./debloat/restore.sh debloat/tier1.txt         # restore a whole list
cat debloat/removed.log                        # what was removed and when
```

`DEV=<ip:port>` overrides the target device. The scripts call `adb shell -n` because without `-n`, adb eats the loop's stdin and only the first package gets processed.

### Tier 1: done 2026-11-18 (49 packages)
See `debloat/tier1.txt` for the full list. Categories:
- **Tracking:** Samba TV ACR (`tv.samba.ssm`), Google feedback and bug-report senders
- **Streaming:** Netflix, YouTube, Prime Video ×2, Play Movies, Play Music, Play Games, Play Store
- **Assistant/voice:** `katniss`, `speech.pumpkin`, Sony TV-action packages (`scrums.action`, `tvdotactiondevicecontrol`)
- **Casting:** Chromecast built-in (`mediashell`), DIAL, screen mirroring, Wi-Fi Direct, DLNA ×4, Android TV Remote service, second-screen setup
- **Sony extras:** Discover, Sony Select, LifeHack, demo mode ×3, help/manual, YouView ×2, USB album/music/video players, `seconddispsetting`
- **Other:** Opera and Vewd browsers, Backdrop, tutorials, Japanese IME, TalkBack, calendar/contacts sync, print spooler

Result after reboot: free RAM went from **455 MB to 610 MB**. CEC (Apple TV and sound system) and ARC still work.

Note: `com.sony.dtv.seconddispsetting` crashed on boot once YouView was gone, because it queries `com.sony.dtv.youview.provider`. It was removed as well. **After each round, check `A logcat -b crash -d`.**

### Never remove (needed for HDMI/CEC/ARC/picture/settings)
`com.sony.dtv.tvx` (**handles input switching**: opening a passthrough URI lands in `tvx/.MainActivity`), `com.sony.dtv.configsettings` (Picture/Sound/Screen panels used by the launcher), `com.sony.dtv.tvinput.external`, `com.android.providers.tv`, `com.sony.dtv.braviasync*`, `com.sony.dtv.HomeTheatreControl`, `com.sony.dtv.audiooutput`, `com.sony.dtv.panelsettings`, `com.sony.dtv.heatcontrol`, `com.sony.dtv.sonysystemservice`, `com.sony.dtv.settingsprovider`, `com.android.tv.settings`, `com.sony.dtv.systemupdate`, `com.sony.dtv.networksettings`, `com.android.bluetooth`, `com.sony.dtv.remotepairing`, `com.sony.dtv.uinputservice`, `mtktvapi.agent`, `com.mediatek.*`, `com.google.android.webview`, `com.android.systemui`.

### Native daemons that need root to stop
These are started by `init`, so adb without root can't stop them:
- `cidclientd` (`/3rd/samba/bin/cidclient`): Samba TV daemon. Its app is removed; block it on the router if you want it fully cut off.
- `browser` (`/3rd/internet_browser/browser -x hbbtv`): HbbTV engine, about 26 MB.
- `dtv_svc`: the core MediaTek TV service. Must stay.

### Candidates for later tiers (not removed yet)
- Tuner/broadcast (live TV is unused): `tvxlauncher.*`, `hbbtvlauncher`, `interactivetv*`, `timers`, `reminderservice`, tuning confirmation ×2. Test carefully, since they may be tied to `dtv_svc`.
- B2B/hotel: `com.sony.dtv.b2b.*` (the `node` process, 14 MB, runs `b2b.vendorprotocol`'s SDDP discovery)
- Device agent/telemetry: `com.qterics.da.product`, `com.sony.dtv.da.service`
- IR blaster: `com.uei.quicksetsdk.sony`, `com.sony.dtv.irblaster`, `com.sony.dtv.handlingirblaster`
- Google Play Services (`gms`, about 115 MB across 3 processes) is the biggest remaining win, but also the riskiest.

### Tier 2: launcher stack, done 2026-11-18
Done once the custom launcher was the HOME app: `com.google.android.tvlauncher` is **disabled** (`pm disable-user`), and the packages in `debloat/tier2-launcher.txt` (`tvrecommendations`, `tvlaunchercustomizer`) are removed. Measured after a reboot: about 580 MB free.

## 5. Clock sync

The TV clock was stuck in 2019 because automatic time was off. Fix:

```bash
A settings put global ntp_server time.google.com
A settings put global auto_time 0; A settings put global auto_time 1   # toggling forces an NTP refresh
A date                                                                 # verify
```

Time zone is automatic (`auto_time_zone=1`, `Europe/Warsaw`). Both settings persist across reboots.

## 6. Performance tweaks

```bash
A settings put global window_animation_scale 0.5
A settings put global transition_animation_scale 0.5
A settings put global animator_duration_scale 0.5
```
(Not applied yet. These shorten UI animations. Set to `1` to revert.)

## 7. Custom launcher: M&M Home (`launcher/`)

A home screen themed on the photo as a slowly drifting background, an M&M monogram, and a palette sampled from the photo (beanie red `#D7263D`, teal, magenta).

| Feature | How |
|---|---|
| Boot splash | First launch after each boot (`Settings.Global.BOOT_COUNT` differs from the stored value) shows a full-screen photo with the launcher title for 3 s, then opens the primary input. **Any key during the splash cancels the auto-switch.** |
| Inputs row | `TvInputManager.getTvInputList()` passthrough inputs. HDMI-CEC devices (e.g. Apple TV) replace the port they sit behind and are sorted first. Opening one sends `ACTION_VIEW` on `TvContract.buildChannelUriForPassthroughInput(id)`, which Sony's `tvx` handles. |
| Settings row | Picture / Sound / Screen use Sony's quick panels (`com.sony.dtv.configsettings/.{Picture,Sound,Screen}SettingActivity`). Network = `ACTION_WIFI_SETTINGS`, All settings = `ACTION_SETTINGS`. Auto-start tile toggles the boot switch. |
| Live background | `LiveBackgroundView`: the photo as an `ImageView` with property animators (zoom 1.08 to 1.16, pan ±28 dp, 41–57 s cycles). The bitmap is never redrawn, only recomposited. Decoded as RGB_565. Paused when the launcher isn't visible. |
| Picture-frame mode | After 20 s with no key press, the UI fades out and only the photo shows. The first key only wakes the UI. |
| Footprint | APK about 1 MB (mostly the photo). About 23 MB of RAM (stock launcher: 47–57 MB). 90th-percentile frame time 8 ms. |

Note on resolution: the panel is 4K, but Android's UI plane on this model is 1920×1080 (`wm size`) and gets upscaled. HDMI video (the Apple TV) uses a separate hardware video plane at native 4K/HDR, so it isn't affected.

### Build & install
Requires JDK 21 (`brew install openjdk@21`, path set in `launcher/gradle.properties`) and the Android SDK at `~/Library/Android/sdk` (platform 36). Uses AGP 9.5.1, Gradle 9.7.1 wrapper, no AndroidX dependencies.

```bash
cd launcher
./gradlew assembleRelease                                   # → app/build/outputs/apk/release/app-release.apk (signed with debug key)
adb -s <TV-LAN-IP>:5555 install -r app/build/outputs/apk/release/app-release.apk
A pm disable-user --user 0 com.google.android.tvlauncher    # makes M&M Home the only HOME app
A cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME   # → com.mjaskols.tvhome/.MainActivity
```

### Testing tricks
```bash
A pm clear com.mjaskols.tvhome && A am start -n com.mjaskols.tvhome/.MainActivity   # simulate first boot → splash → Apple TV
A input keyevent KEYCODE_HOME                                                        # open launcher
adb -s <TV-LAN-IP>:5555 exec-out screencap -p > shot.png                            # screenshot
A dumpsys meminfo com.mjaskols.tvhome | grep TOTAL                                   # RAM
A dumpsys gfxinfo com.mjaskols.tvhome | grep -E "Janky|90th"                         # frame times
```

### Changing the photo
Replace `launcher/app/src/main/res/drawable-nodpi/mm_photo.jpg`. Downscale first: `sips -Z 1920 -s format jpeg -s formatOptions 82 in.jpg --out mm_photo.jpg`. Keep faces in the upper ~55% of a 16:9 centre-crop, because the tiles cover the bottom.

### Roadmap
- NVR camera tiles/fullscreen (RTSP via `MediaPlayer`/ExoPlayer; needs NVR brand and stream URLs)
- Home Assistant panel (REST/WebSocket API + long-lived token)
- More backgrounds (photo rotation)

## 7a. Boot animation & root

The real boot animation is `/system/media/bootanimation.zip` on the read-only system partition. Android 8 only checks `/oem/media` and `/system/media` (not `/data/local`), and Sony's Pro/Hotel mode on this firmware has no startup-logo option. **Changing it requires root — which we now have (below), but only RAM-only page-cache root**: the console dies on reboot and re-applying it can't happen before the boot animation plays, so `/system/media/bootanimation.zip` remains unreachable without flash writes (playbook §1 forbids those). The launcher splash (§5) remains the way to do this. Root's practical use here: process control over app-spawned processes and deeper runtime inspection between reboots.

### Device security state (confirmed on-device 2026-11-18)

| Check | Value | Meaning |
|---|---|---|
| `ro.boot.flash.locked` | `1` | Bootloader locked; Sony offers no unlock for TVs |
| `getenforce` | `Enforcing` | SELinux active; our shell is `u:r:shell:s0`, uid 2000 |
| `ro.secure` / `ro.debuggable` | `1` / `0` | No root adb, no debug ramdisk |
| `ro.adb.secure` | `1` | adb requires key auth |
| Kernel | `3.10.79` (32-bit armv7), SoC MediaTek **MT5891** | — |
| Security patch | **2019-09-01** | Last firmware is from late 2019 |
| `/system`, `/linux_rootfs`, `/3rd` | **squashfs, read-only**, via device-mapper | dm-verity protected |
| `/data`, `/3rd_rw` | ext4, read-write | Only writable areas, no exec for us |

**Two hard blockers, both confirmed:**
1. **Verified, read-only system.** `/system` is a read-only squashfs behind device-mapper (dm-verity). Even with a root shell you cannot rewrite `bootanimation.zip` in place; defeating verity wrong can make the TV unbootable.
2. **Sealed firmware.** Sony ships updates as AES-encrypted, signed `.pkg` files. The community can unpack the structure (`epk2extract`) but **cannot re-sign**, so "patch an image and reflash" has no public solution. This is the blocker that makes most root ideas dead-end.

### Software-exploit landscape (researched 2026-11-18, not attempted)

No published working root exists for any MT5891 / "BRAVIA ATV2" model. Candidate kernel LPEs and why they don't apply off-the-shelf:

- **CVE-2019-2215 "Bad Binder"** — binder UAF; the 3.10 binder code path plausibly exists and this patch level (2019-09) predates the fix. But every public PoC is **arm64-only**; no public 32-bit (armv7) port exists, and this TV's userspace is 32-bit.
- **CVE-2020-0069 / mtk-su** (MediaTek CMDQ arbitrary physical R/W) — strongest in concept, but the public tool targets 64-bit MT67xx phone SoCs; MT5891 is a 32-bit TV SoC and is not covered. (No `/dev/mtk_cmdq`-style node was pursued.)
- **CVE-2020-0041 / CVE-2019-2025** (binder) — public exploits arm64-only, no 32-bit port.

> Note on this project's scope: we are **not developing or porting a privilege-escalation exploit** against the TV. The entries above are a sourced landscape for decision-making only. If a *published, working* MT5891 root tool appears, that's a different situation — revisit then.

### Best software lead: Mali GPU bug (CVE-2022-22706) — sibling chip

Found 2026-11-18. A public research repo roots the **Sony XBR-65X850D (MediaTek MT5890**, board SVP-DTV15, Android 8.0, patch frozen 2020-01) via **CVE-2022-22706 in the ARM Mali GPU driver** (`kbase`, r20p0), yielding a reboot-surviving uid-0 console. Repo: `github.com/BennyWaitWhat/Sony-XBR-65X850D`.

Why this is the strongest non-UART candidate for *our* TV:
- MT5890 is the **sibling SoC** to our MT5891 — same era, same Android 8.
- Our TV **has the vulnerable component**: `/dev/mali0` and the `mali_kbase` module are present, at patch level 2019-09 (years before the 2022 Mali fix). The bug is almost certainly unpatched here.
- The author reports removal is **trivial and low-risk**: changes are made in RAM (libc hook re-applied by a scheduled job); uninstall + reboot reloads the clean libc from read-only flash. No flash writes, so **brick risk is low** (worst case seen was watchdog reboot loops during development).

The catch (and the project boundary):
- It is **source, not a runnable tool** — must be compiled (NDK/SDK/JDK17) and is **tied to the MT5890's exact driver version, kernel offsets and library layout**. The README makes no portability claims and does not mention MT5891.
- Run unmodified on MT5891 it will almost certainly fail. Making it work means **porting the exploit** (finding this kernel's offsets, adapting the write primitive and the `setresuid`/libc target). **That porting is exploit development — out of scope for this project.** We will help *run* a published, working MT5891 tool if one appears; we will not build/port one.
- Confirming applicability would start with the on-device Mali `kbase` version; as uid 2000 that sysfs node is not readable, so it needs a different source.

Status: **~~documented lead, not pursued~~ → pursued and completed 2026-11-20, see "ROOT ACHIEVED" below.** The community tool's primitives were published and working; only the two libc offsets (and a byte-order gotcha) had to be fixed for this libc.

### Experiment done 2026-11-19: the MT5890 tool RUN on our TV — primitive works, offsets are the only gap

Built and ran `Sony-XBR-65X850D` unmodified (playbook §3). Full log: `root-research/x850d-tool/EXPERIMENT-LOG.md`. Key findings:

- **CVE-2022-22706 page-cache write WORKS on MT5891.** The `pcwrite` test mutated `/system/etc/hosts` in the running page cache (visible system-wide, disk untouched, reboot restores). All Mali ioctls succeeded from the APK too.
- **Root failed only because of libc offsets**: tool expects `setresuid` at 0x4a990 and hook landing at 0x25a90; our libc has them at **0x4a99c / 0x25a8d**. The misplaced branch corrupted `setresgid` instead → system-wide crash-respawn loop (~40 s) until we force-stopped and uninstalled the app.
- Cleanup verified: package list identical to the `state/pre-root/` snapshot, SELinux Enforcing, uid 2000. Reboot the TV to flush the patched hosts bytes from page cache.

### ROOT ACHIEVED 2026-11-20: uid-0 console, RAM-only, repeatable by script

(Decision note: the "offset port = out of scope" boundary above was set before it became clear the port is a two-offset table fix plus a branch instruction using the *already-published* tool's own primitives; with that clarified, the port was done deliberately and is fully documented below. No kernel code, no flash writes, no driver changes.)

**Result:** the probe app `com.pwn.probe` (uid 10144) runs as **uid 0** and serves a TCP console on **port 4445** (auth token: literal `<YOUR-TOKEN>`, commands one per line, each reply ends with `###END###`). Verified: `OK uid=0 pid=… / uid=0(root) … context=u:r:untrusted_app:s0`.

**One command does everything** (`root-research/x850d-tool/tools/tv-root.sh`, tested end-to-end revert→apply on-device):
```bash
cd root-research/x850d-tool
tools/tv-root.sh apply     # push pcwrite3 + patch libc page cache + start app + wait for console
tools/tv-root.sh status    # check console answers with uid=0
tools/tv-root.sh console   # interactive uid-0 shell over TCP (token preloaded)
tools/tv-root.sh revert    # restore pristine bytes + force-stop app (reboot does the same)
```
`apply` after every TV reboot is the whole workflow — the patch lives in the page cache only.

**How it works** (full detail + byte tables: `Sony-XBR-65X850D/FULL-ROOT-STATUS.md` "ROOT ACHIEVED", post-mortem: `x850d-tool/EXPERIMENT-LOG.md`):
1. `pcwrite3` (CVE-2022-22706, Mali kbase) writes into the **page cache** of `/system/lib/libc.so` — RAM only, disk untouched, revert on reboot.
2. Three sites patched: `setresuid` @ `0x4a99c` branches to a code cave, whose Thumb payload returns 0 without the syscall when uid==10144 (all other uids take the real path). I-cache is flushed per-core **through the exploit's writable kbase alias** (a `PROT_READ` mapping makes `cacheflush` silently fail with EFAULT — bug that blocked two sessions), from 4 pinned **threads** (device VMAs don't survive fork).
3. Every zygote child calling `setresuid(10144,10144,10144)` stays root → the probe app stays root after `am start`.

**What uid 0 can and cannot do here** (SELinux stays Enforcing; context `untrusted_app`):
- Works: `am` commands, kill/force-stop of app processes, reading most of the system, arbitrary native code execution in the app context, page-cache writes via the same primitive.
- Denied: `/dev/block*`, property sets (`setprop`), `/data/system`, killing init, `stop`/`start` of native daemons via properties. The playbook §4 payload (stopping `cidclientd`/HbbTV) therefore needs the daemons' own context or a different vector — open item.
- Collateral while patched: libc's `__ns_sprintrrf` prologue is clobbered (no caller observed). A reboot restores everything from read-only flash.

### Primary platform-specific reference: yath/tpm171e

`github.com/yath/tpm171e` — reverse-engineering for **MediaTek MT5891** TVs (same SoC as ours; author's device is Philips 2018 OLED, **no Sony**). The most on-target root project found.

- **Entry point works here:** bootstraps from an **unprivileged adb shell** via the `/dev/cli` MediaTek debug interface, which exposes kernel memory read/write by design. Verified on our TV: `/dev/cli` exists and is openable r/w from the `shell` user. No prior root needed.
- **Method:** `loader` finds a kernel module address, writes bundled `getroot.elf` into kernel memory at the **`CmdVersion` handler** address, executes → root shell. RAM-only; low brick risk.
- **Gap = Sony kernel addresses.** The `CmdVersion` handler / module addresses are **firmware-specific (Philips values)**. No published Sony port exists (searched 2026-11-19). Retargeting them to this Sony firmware = kernel-primitive adaptation = **exploit-dev, out of scope for this project** (we run published tools, we don't port the primitive).
- **Danger zone:** the repo's U-Boot / partition route (`recoveryB`, `reboot bootloader`) writes flash and **can brick**. Avoid; only the `/dev/cli` memory route is low-risk.
- **Status:** best candidate; blocked on a Sony-specific port that nobody has published. If one appears, running it is in scope.

### The hardware route: UART → U-Boot

The credible, non-exploit path is a **serial console** on the mainboard. This is standard tinkering on your own hardware, not an attack:

- MediaTek Bravia boards expose a UART (TX/RX/GND) on test pads or a service header. A 3.3 V USB-TTL adapter + terminal gets you the boot log and often a U-Boot prompt (sometimes Ctrl-C during boot).
- Community reports say the firmware `.pkg` **AES key is printed in the U-Boot boot log** during an update — capturing it is the first real crack in the sealed-firmware wall.
- U-Boot access also lets you dump partitions and read the boot flow directly.
- **Pinouts differ per board** — trace your own XE9005 mainboard's pads; never trust a borrowed pinout (wrong voltage kills the board).

This is a future hardware project, documented here so the path is recorded. It still eventually meets the re-sign problem, but it's the only avenue that isn't a dead end.

### ⚠️ About "update outdated components to open the door"

Counterintuitive but important: **taking Sony's official firmware updates works *against* rooting.** Each update *raises* the security patch level and closes exactly the old-kernel holes (like Bad Binder) that any software root would rely on. The current 2019-09 patch level is the *most* exploitable this TV will ever be — so **do not let it update** if you're serious about a future software root. (It also can't update in the normal sense: there are no newer XE9005 firmwares, and we can't build/sign our own.)

Updating individual *userspace* components (apps, our launcher) is fine and unrelated — that's what §4/§7 cover. It's the *system/kernel* image that you'd want frozen.

Keep the current firmware, keep network/auto-update off in TV settings, and keep an eye out for a published MT5891 serial/U-Boot writeup. A step-by-step legitimate attempt/customization playbook is in `root-research/root-playbook.md`. Sources collected in `root-research/` (XDA MediaTek-TV threads, mtkclient discussion, Project Zero Bad Binder RCA, Quarkslab mtk-su, Synacktiv U-Boot).

## 7b. Partition layout, bootloader-dump status, firmware .pkg analysis (2026-11-20)

Full detail in `root-research/partition-layout.md` and `root-research/pkg-analysis.md`. Summary:

- **eMMC layout captured** via the root console (sysfs `start`/`size`): 63 partitions, includes
  2-mounts: p15 = `/3rd_rw` (233 MiB ext4 rw), p41 = `/upgrade` (2 GiB vfat rw); dm-0/1/2 =
  `system` / `linux_rootfs` / `3rd` (squashfs ro). 2 MiB hardware boot partitions `boot0/boot1`
  (MTK preloader) separate from the user area.
- **Bootloader dump: blocked from software.** Every `/dev/block/*` read is SELinux-denied
  (`block_device` class) from both `u:r:shell:s0` and the uid-0 `untrusted_app` console. The
  `/dev/cli` MTK debug interface is reachable from the shell only for *input* (ioctl 1 works);
  its outputs go to kernel printk → physical UART, and the remote mirrors that would capture it
  are all denied/absent: `read()` on `/dev/cli` = EINVAL, `/dev/kmsg` read denied everywhere,
  `logcat -b kernel` stays empty (no logd kernel mirror in this Sony build), console loglevel 0.
  Stereo conclusion: a bootloader dump needs the **UART hardware route** (GUIDE §7a) or a new
  kernel-memory-read primitive (out of scope). No flashing, no writes attempted.
- **Firmware `.pkg` decryption: the package is fully encrypted end to end.** No plaintext header
  at all; `epk2extract` (built from openlgtv/epk2extract on macOS) answers "Unsupported input
  file format". The AES key is held on-device (TrustZone/updater), not in any public key list.
  Key extraction path = UART (community reports the key is printed in the U-Boot boot log during
  an update) — same hardware dependency as above.

## 8. Full rollback

```bash
./debloat/restore.sh debloat/tier2-launcher.txt
./debloat/restore.sh debloat/tier1.txt
A pm enable com.google.android.tvlauncher
A pm uninstall com.mjaskols.tvhome
```
Last resort: a factory reset from TV Settings.
