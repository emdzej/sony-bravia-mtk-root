#!/usr/bin/env bash
# Usage: apply.sh <list.txt>   — hides packages for user 0 (reversible via restore.sh)
set -u
DEV=${DEV:-<TV-LAN-IP>:5555}
LOG="$(dirname "$0")/removed.log"
grep -vE '^\s*(#|$)' "$1" | while read -r pkg; do
  out=$(adb -s "$DEV" shell -n pm uninstall -k --user 0 "$pkg" 2>&1)
  printf '%-45s %s\n' "$pkg" "$out"
  [[ "$out" == Success* ]] && echo "$(date +%F) $pkg" >> "$LOG"
done
