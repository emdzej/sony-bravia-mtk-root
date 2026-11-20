# Sony ATV2 firmware `.pkg` analysis — `sony_dtv0FA60A0AA0AA_00004100_16072201000365.pkg`

Analyzed 2026-11-20 (read-only, mmap scans on macOS). File: 1,701,302,154 bytes,
sha256 `a60ac5c3d3815494184a2a4c4308a8cb684490aef0e1495bcdfa551e477a1c90`.

## Finding: the package is fully encrypted

- No plaintext run longer than 21 characters anywhere in the 1.6 GB (scanned with a
  printable-run heuristic across the whole file).
- No structural markers anywhere: no `EPK`, no GPT (`EFI PART`), no `ANDROID!`, no zip/local
  headers, no `SONY` string. The two `PHIL`/`Phil` hits found are random ciphertext
  coincidences.
- Byte 0 is already high-entropy — even the outer header is encrypted, unlike Philips/Hisense
  MTK packages where a clear `#DH@FiRm` header precedes per-partition AES.

## What the standard tool does and why it fails here

`epk2extract` (openlgtv/epk2extract, built successfully on macOS, version 4.8) detects MTK
packages by brute-forcing its bundled AES keys against the clear header: it checks
`#DH@FiRm`/vendor magics (Hisense `hise`, Philips `PHIL`, `TPV_`, Sharp) after AES-CBC
decryption with the keys in `keys/` (incl. `AES.key`, `MTK.key`). Result on this file:

```
Unsupported input file format
```

So the Sony ATV2 container either uses a different header structure or (more likely, given the
zero-plaintext scan) a Sony-held AES key that is not in any public tool. The DooMLoRD guide
("[LINUX] UNPACKING/EXTRACTING & REPACKING SONY BRAVIA ANDROID TV FIRMWARE PACKAGES (.PKG)",
sonydit.com/forum/threads/5663/) for ATV2/ATV3 is the reference for the format — the domain is
dead; a mirror/wayback copy is needed.

Where the key lives (inference): on-device. The SystemUpdate APK
(`/system/priv-app/SystemUpdate/SystemUpdate.apk`, pulled and unpacked — Java side only calls
`MtkTvSwUpgrade` JNI in `/system/vendor/lib/libcom_mediatek_twoworlds_tv_jni.so`, which links a
vendor daemon lib that was not enumerable: `/linux_rootfs` directories are not listable under
SELinux). The update engine and its key material (AES key likely in a TrustZone/eeprom-backed
store, decryptable only in-kernel or in the TZ world) live on the TV.

Extraction paths that would work, in order of feasibility:
1. **UART → U-Boot dump** (hardware): community reports say the pkg AES key is even printed
   in the U-Boot boot log during an update. This is the credible path (GUIDE.md §7a).
2. **`/dev/cli` kernel memory read**: the yath/tpm171e route — on the Philips sibling, CLI
   memory-read commands dump physical RAM. On this Sony firmware the CLI *input* path works
   from the adb shell (`ioctl(fd, 1, cmd)` accepted by char 241:4) but there is **no output
   capture from a distance**: `read()` on `/dev/cli` always returns EINVAL, Sony's `cli_shell`
   (at `/system/bin/cli_shell`, present) captures replies by reading `/dev/kmsg` — SELinux
   `kmsg_device` read is denied from every context we hold (shell, untrusted_app uid 0), Sony
   ships no `logcat -b kernel`/`MTK_KL` mirror (logd's kernel buffer stays empty), and console
   loglevel is 0. So CLI command *results* are only visible on the physical UART. This makes
   the software-only CLI route a dead end without hardware (or an additional kmsg-read
   primitive).
3. On-device updater emulation: no.

## Android side checked

- `pm list packages` → only `com.sony.dtv.systemupdate` relates to updates.
- Its dex has no unpacking logic (all `MtkTvSwUpgrade` JNI passthrough):
  `SwUpgradeGenericFunction` func_ids dispatch into the native lib, which is a shim.
- The real engine + decryption runs below the Android shelf.
