#!/usr/bin/env bash
# Usage: restore.sh <pkg>... | restore.sh <list.txt>   — re-enables system packages hidden by apply.sh
set -u
DEV=${DEV:-<TV-LAN-IP>:5555}
if [[ -f "${1:-}" ]]; then set -- $(grep -vE '^\s*(#|$)' "$1"); fi
for pkg in "$@"; do
  printf '%-45s %s\n' "$pkg" "$(adb -s "$DEV" shell -n cmd package install-existing "$pkg" 2>&1)"
done
