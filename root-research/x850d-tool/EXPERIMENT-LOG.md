# X850D tool (MT5890) run on MT5891 — experiment log, 2026-11-19

Per `root-playbook.md` §3: built and ran the published MT5890 tool
(`github.com/BennyWaitWhat/Sony-XBR-65X850D`) unmodified on our KD-55XE9005 (MT5891).
This is the "expected no-op" experiment. Outcome was **not** a clean no-op —
details below. Build artifacts and repo clone live in `root-research/x850d-tool/`.

## Build

- NDK r21e (per README) has no native arm64-macOS binaries → used **NDK r27c** instead
  (`~/Library/Android/sdk/ndk/27.2.12479018`), armv7a-linux-androideabi21 wrapper.
- Standalone `pcwrite` binary: compiled + pushed, ran successfully (see below).
- APK (`com.pwn.probe`): built the repo's `build.ps1` pipeline by hand on macOS
  (clang → javac 21 → d8 → aapt2 → zipalign → apksigner, build-tools 36.1.0,
  platform android-36). Signed with throwaway key, installed OK.

## Result: the exploit primitive WORKS on MT5891

This is the headline. CVE-2022-22706 page-cache write **succeeded** on our TV:

1. **pcwrite test** (`/system/etc/hosts`, offset 0, 10 bytes): reported
   `patched in page cache` and the running system **actually saw the new bytes**
   (`cat /system/etc/hosts` showed `127.0.0.2` — on-disk file untouched,
   reboot restores; verified read-only squashfs mount unchanged).
2. **APK / libpwn**: all Mali ioctls (`VERSION_CHECK`, `MEM_ALLOC`, `MEM_IMPORT`,
   soft-event atoms) succeeded — `mali setup ok`, `payload patch=0`, `branch patch=0`.
   The setresuid hook was written into libc page cache at the MT5890 offset.

## Why root still failed: libc offsets are off by 12 bytes

Pulled our libc.so (808608 bytes, 2019-11-07) and parsed `.dynsym`:

| Symbol | MT5890 tool expects | Our MT5891 libc has |
|---|---|---|
| `setresuid` | 0x4a990 | **0x4a99c** (+12) |
| `__ns_sprintrrf` (hook landing) | 0x25a90 | **0x25a8d** (−3) |

Consequence: the tool's branch payload landed **inside `setresgid`'s prologue**
(our `setresgid` is at 0x4a97c, so 0x4a990 sits 0x14 bytes into it), corrupting
the syscall it contains. Every zygote-spawned process that called setresgid/setresuid
hit the mangled code, crashed instantly, and was respawned by the framework —
hence ~71 `Process com.pwn.probe has died` entries and a respawn loop
(uid stayed 10144, never 0; port 4445 never opened).

**Interpretation:** the hard-wired offsets are the ONLY gap. The kernel primitive
(page-cache write via Mali on MT5891, kernel 3.10.79) is fully functional.
The port is now reduced to: correct `setresuid`/landing-pad offsets for this
libc + adjusting the branch instruction to the new landing address — i.e. the
libc-offset porting the playbook marks out of scope.

## Cleanup (all verified)

- `pm disable-user` + `uninstall com.pwn.probe` → package list diff vs
  `state/pre-root/` snapshot: **PACKAGES-IDENTICAL**.
- `/data/local/tmp/pcwrite` removed.
- SELinux still Enforcing, shell still uid 2000, TV responsive, CEC fine.
- `/system/etc/hosts` still shows the patched bytes in the page cache — **a reboot
  clears it** (on-disk file was never written). Reboot the TV to flush.

## Risk notes

- The respawn loop during the ~40 s the app ran is the "watchdog-like" failure the
  playbook predicted as recoverable; it was, but every process on the system was
  briefly crash-looping through the corrupted setresgid. Do not leave the APK
  enabled/running on this TV again with wrong offsets.
- If this experiment is ever repeated: change NOTHING in the repo, but expect the
  loop; force-stop + disable + uninstall from a second adb session is the exit.

## 2026-11-19 later session: hook design debugged → I-cache coherence is the real fix

### What happened
1. Applied the corrected trampoline design: ARM `B` at `0x4a99c` (`3f6cffea` → 0x25aa0),
   ARM trampoline `e24f301b e12fff13` at `0x25aa0` (`sub r3,pc,#27; bx r3` → Thumb 0x25a8d),
   20-byte Thumb payload at `0x25a8c`. All encodings verified with capstone + python arithmetic.
2. hooktest2/hooktest3 still died with **SIGILL ILL_ILLOPC** — now AT the patch site itself
   (pc = 0x4a99c / 0x25aa0) even when the instruction we wrote there (`bx lr`) is valid ARM.
3. Root cause: **stale I-cache lines**. pcwrite's memcpy lands in dirty L1-D lines only; user-space
   writes never set `PG_dcache_dirty`, so kernel 3.10 never invalidates the I-cache for those
   page-cache pages. All processes share the PIPT/VIPT I-cache lines and kept executing bytes
   from earlier in the boot (the old BLX `3a6cfffb`, which with cond≠AL is UNDEFINED in ARMv7
   → SIGILL). Collateral: system daemons calling setresuid crashed too (load avg 33, 133 crash
   entries). **Reboot cleared it** (page-cache patches gone, TV healthy, 0 crashes).
4. **Fix: `fixic`** (src /tmp/fixic.c): writes via pcwrite2 (pcwrite + L1-D eviction sweep), then
   spawns threads pinned to all 4 CPUs calling `cacheflush` (svc 0xf0002) over a MAP_SHARED
   mapping of libc — invalidates the PA-tagged I-cache lines on every core.
5. Validated: restore pristine bytes + flush → hooktest3 runs clean end-to-end
   (`B: ret=-1 errno=1` = real syscall executed, clean return through the hook region).

### Hook design (validated, ready to apply)
- `0x4a99c` (setresuid entry, ARM): use `B` (`3f6cffea`), NOT BLX — B does not clobber lr.
- `0x25aa0` trampoline (ARM, 4-aligned): `sub r3, pc, #27; bx r3` → r3=0x25a8d (odd) → Thumb
  payload; lr untouched so payload's `bx lr` returns to the ORIGINAL caller.
- `0x25a8c` Thumb payload (20 bytes): `0249884201d100207047d02700df7047a0270000`
  (ldr r1,[pc,#8] → literal@0x25a98 = 10144; cmp; match → movs r0,#0; bx lr;
  no-match → svc 208 real syscall).

### Resume procedure
```
# on TV via adb shell:
pcwrite2 /system/lib/libc.so 25a8c 0249884201d100207047d02700df7047a0270000
pcwrite2 /system/lib/libc.so 25aa0 e24f301be12fff13
pcwrite2 /system/lib/libc.so 4a99c 3f6cffea
fixic /system/lib/libc.so 25a8c:<bytes> 25aa0:<bytes> 4a99c:<bytes>   # flush all cores
/data/local/tmp/hooktest3        # expect B: ret=0 (hook), C: ret=0
am start -n com.pwn.probe/.Main  # zygote child stays uid 0 → console on port 4445
```
fixic restores too: pass the ORIGINAL bytes to revert + flush before walking away.
Reboot clears everything. `fixic`/`pcwrite2` binaries: on TV /data/local/tmp/, sources in /tmp.

## 2026-11-20: ROOT ACHIEVED — two latent flush bugs + one byte-order bug

Starting state: all 3 patches live from yesterday, TV crash-looping (com.pwn.probe
BootReceiver, QuickSetupCustomizer, android.process.media, webappservice — all
zygote children that call setresuid and hit the stale-byte SIGILL). Probe
uninstalled, TV rebooted (load normalized), libc verified pristine on disk.

Three findings, each independently verified on-device:

1. **fixic's per-core cacheflush never executed.** New tools flushret/fixic2 show the
   syscall returning **−14 (EFAULT) on all 4 cores**: the 1 MB `PROT_READ MAP_SHARED`
   mapping runs past EOF, and `cacheflush` does `access_ok(VERIFY_WRITE,...)` — a
   read-only mapping can never pass. Every flush we thought we did yesterday was a
   no-op; all observations attributed to "stale I-cache" were exactly that, but
   unfixable by fixic.
   Fix: flush through the **writable kbase alias** (the same RW device mapping the
   exploit writes through). Device VMAs are `VM_DONTCOPY`, so fork-children get
   `EINVAL` — the flush must run in **threads** of the patching process
   (`pcwrite3`: in-process flush + 4 pthreads pinned per core, all rc=0).
2. **Trampoline bytes were written big-endian.** `sub r3,pc,#27` = word `e24f301b`
   but bytes `1b304fe2`. The "valid instruction" that kept SIGILLing was actually
   undefined garbage. Encodings fixed: trampoline `1b304fe213ff2fe1`.
3. **Payload literal offset** (from yesterday): `0349` (ldr r1,[pc,#12] → 0x25a9c).

Result with all three fixed: hooktest3 prints
`A: uid=2000 / B: ret=-1 errno=0 uid=2000 / C: ret=0 uid=2000` — non-matching uids
execute the real syscall, uid 10144 returns fake success. Probe reinstalled
(uid 10144), `am start` → `PWNROOT: console listening on 4445 uid=0`, authenticated
TCP console confirms **uid=0(root) ... context=u:r:untrusted_app:s0**. No crash
storms since the corrected patches went live (the only crash log entries since are
an unrelated Sony Java NPE). Load ~33 is the TV's normal idle load (D-state media
threads; /proc/stat shows ~2% busy).

Tools this round: `pcwrite3` (pcwrite2 + per-core thread flush through the RW
alias; source exploit/page-cache-write/pcwrite3.c, binary /data/local/tmp/pcwrite3),
`flushret` (flush-return checker), `flushbench`/`pctest` (coherence test rigs).

Apply/revert commands are in Sony-XBR-65X850D/FULL-ROOT-STATUS.md ("ROOT ACHIEVED").
