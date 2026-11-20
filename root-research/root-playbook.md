# Root playbook — legitimate maximum

This is the "as far as we can go" writeup for rooting the KD-55XE9005 (MT5891). It deliberately **excludes the exploit-development core** — discovering MT5891 kernel offsets, building the Mali kernel-write primitive, or weaponizing the cred/libc patch. That part is a transferable kernel LPE and is out of scope for this project (see `../GUIDE.md` §7a and the reasoning recorded there). Everything below is the legitimate surround: safe attempts with published tools, how to read the outcome, and what to do with root once it exists.

---

## 0. Boundary, in one line
We will **run/compile a published, working root tool** and **use root once obtained**. We will **not develop or port the kernel exploit** that produces root on a chip no public tool covers yet.

---

## 1. Safety facts (confirmed on-device 2026-11-18)

Why root attempts here are low-risk *as long as nothing writes to flash*:

- `/system`, `/linux_rootfs`, `/3rd` are **read-only squashfs** behind device-mapper (dm-verity). They cannot be modified in place, which also means a RAM-only root **cannot corrupt them**.
- Only `/data` and `/3rd_rw` are writable, and neither is where boot/system lives.
- The sibling-chip research (MT5890) reports its root is **RAM-only**: a userspace hook re-applied by a scheduled job, removed by uninstalling the app and rebooting (clean libc reloads from read-only flash). **No flash writes → brick risk is low.** Worst observed failure was a self-recovering watchdog reboot loop.

**Hard rule for any attempt:** nothing in this project flashes a partition, writes to `/dev/block/*`, disables dm-verity, or modifies a boot/system image. Those are the only things that can actually brick the TV, and they're also blocked by Sony's signing. If a tool wants to do any of that, stop.

### Recovery ladder (if an attempt misbehaves)
1. RAM-only changes vanish on **power-cycle** (pull mains 10 s).
2. Uninstall any pushed app: `adb -s <TV-LAN-IP>:5555 uninstall <pkg>` then reboot.
3. Clear its data: `A pm clear <pkg>`.
4. Last resort: **factory reset** from TV Settings → restores all debloated apps too (`../GUIDE.md` §8).

---

## 2. Pre-flight snapshot (do this before any attempt)

```bash
A(){ adb -s <TV-LAN-IP>:5555 shell "$@"; }
mkdir -p ../state/pre-root && cd ../state/pre-root
A getprop > props.txt                      # full baseline
A pm list packages -f > packages.txt
adb -s <TV-LAN-IP>:5555 pull /sys/fs/selinux/policy sepolicy 2>/dev/null
A dumpsys meminfo > meminfo.txt
A ps -A > ps.txt
```
Keep this so any post-attempt diff is obvious and you can prove the device is unchanged if an attempt no-ops.

---

## 3. The only legitimate attempt available today: the published sibling tool, as-is

There is **no published MT5891 root**. The closest published work targets the **MT5890** (sibling chip): `github.com/BennyWaitWhat/Sony-XBR-65X850D`, CVE-2022-22706 (Mali `kbase`).

What you may legitimately do with it:
- **Clone and build it unmodified** (NDK + SDK build-tools + JDK 17, per its README). Building published source is fine.
- **Push and run the resulting binary/APK as-is** on your own TV.

What will almost certainly happen: it is hard-wired to the MT5890's driver version, kernel offsets and library layout. On MT5891 it will most likely **fail cleanly or trigger a watchdog reboot** (recoverable per §1). That's the expected, acceptable outcome of trying published code on a near-but-not-identical target.

What you may **not** get from this project: the adaptation that would make it actually succeed on MT5891. That adaptation is the exploit-porting work we don't do.

### Reading the outcome
After running the tool as-is:
```bash
A id                                   # uid=0 would mean it worked (it won't, unmodified)
A getenforce                           # Permissive would indicate SELinux was dropped
A dmesg 2>/dev/null | tail             # only readable if root was gained
```
- `uid=2000` + tool exits/errors + TV fine → **no-op, expected.** Nothing changed; confirm with a `getprop`/`packages` diff against §2.
- Watchdog reboot → wait for boot, reconnect adb, verify baseline. No action needed.

### If/when a real MT5891 tool appears
If someone publishes a working MT5891 port or prebuilt tool, bring it here — **running it is in scope** and §1/§4 apply directly.

> **Update 2026-11-19 — §3 experiment EXECUTED.** The MT5890 tool was built (NDK r27c on arm64 macOS, not r21e — no native binaries) and run unmodified. Result: **the CVE-2022-22706 page-cache write primitive works on MT5891** (verified with the harmless `/system/etc/hosts` test), and the run failed only at the libc-offset step — `setresuid` is at 0x4a99c here (tool: 0x4a990), landing pad at 0x25a8d (tool: 0x25a90). The misplaced hook corrupted `setresgid` and caused a system-wide crash-respawn loop for ~40 s until the app was disabled+uninstalled. Full log: `x850d-tool/EXPERIMENT-LOG.md`. Do **not** rerun without addressing the loop risk; the remaining work is the out-of-scope offset port.

---

## 4. Post-root customization payload (ready for when root exists)

These are your actual goals. They're written so that the day root exists (by any means you obtain legitimately), the customization is a copy-paste — no exploit content required.

> All of these assume a **root shell** (`su` / uid-0 console). None of them require touching signed partitions.

### 4a. Boot splash — the realistic options
The true `/system/media/bootanimation.zip` sits on read-only, verity-protected squashfs, so **even with root you don't edit it in place.** Realistic root-enabled approaches, least-invasive first:

1. **Bind-mount overlay (RAM-only, reversible, no flash write)** — mount a replacement `bootanimation.zip` from `/data` over the system path at early boot via an init script the root solution installs. Survives reboot via the root's own persistence, vanishes if root is removed. This is the safe one.
2. <launcher-pkg> splash (custom launcher, separate private project) covers 90% of the "personal boot screen" goal without root.

Avoid: repacking the squashfs or disabling verity to edit the real file. That's the brick-and-signing wall; not worth it for a logo.

### 4b. Stop the native daemons debloating couldn't reach
With root these can finally be disabled (identified in `../GUIDE.md` §4 "Native daemons"):
```bash
# as root, RAM-only — reverts on reboot unless persisted by the root solution
stop cidclientd           # Samba TV ACR daemon (/3rd/samba/bin/cidclient)
# HbbTV 'browser' engine and 'node' SDDP: stop their init services similarly
```
Persist only through the root solution's own init hooks — do not edit read-only `/system` init.

### 4c. Deeper debloat / freezing
Root lets you fully stop (not just user-hide) the remaining B2B/telemetry services from `../GUIDE.md` §4 "Candidates." Same rule: RAM-level `stop`/`pm disable` and persist via the root layer, never a flash write.

---

## 5. Keep the window open: freeze the firmware

The 2019-09 patch level is the **most rootable state this TV will ever be in** — it predates the Mali fix (and Bad Binder). Any Sony update closes those.

```bash
# In TV Settings, not adb:
#   Settings → Device Preferences → About → System update → OFF / never
#   Keep "Automatically check for updates" disabled
```
There are also no newer XE9005 firmwares and we can't sign our own, so there's nothing to gain and the one thing to lose by updating.

---

## 6. Summary

| Want | Status | How |
|---|---|---|
| Personal boot screen | **Done, no root** | Launcher splash, `../GUIDE.md` §7 |
| Run a published MT5891 root tool | **Ready if one appears** | §3, §1 safety |
| Try sibling (MT5890) tool as-is | **You may, expect no-op** | §3 — build/run unmodified |
| Port exploit to MT5891 | **Out of scope** | We don't build kernel LPE primitives |
| Kill native daemons / deep freeze | **Ready once root exists** | §4b–c |
| Replace real bootanimation.zip | **Possible w/ root, not advised** | §4a option 1 only; never defeat verity |
| Keep root reachable | **Action: freeze firmware** | §5 |
