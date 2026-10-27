#!/bin/sh
# Single-threaded backup loop: logical dump, base backup, WAL ship, restore drill.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
. "$script_dir/lib.sh"

interval=${BACKUP_WAL_SHIP_INTERVAL_SECONDS:-60}
case "$interval" in
  ''|*[!0-9]*) interval=60 ;;
esac
if [ "$interval" -lt 30 ] || [ "$interval" -gt 3600 ]; then
  interval=60
fi
daily=${BACKUP_DAILY_UTC:-18:15}
case "$daily" in
  [0-2][0-9]:[0-5][0-9]) ;;
  *)
    echo "BACKUP_DAILY_UTC must be HH:MM" >&2
    exit 1
    ;;
esac

time_reached() {
  first=$(printf '%s\n%s\n' "$daily" "$1" | sort | head -n 1)
  [ "$first" = "$daily" ]
}

echo "backup scheduler started"
while true; do
  now_epoch=$(date -u +%s)
  today=$(date -u +%Y-%m-%d)
  now_hm=$(date -u +%H:%M)
  last_backup=$(state_get backup-success-epoch)
  marker=$(cat "$STATE_DIR/daily-marker" 2>/dev/null || true)
  if [ "$last_backup" -eq 0 ] || [ $((now_epoch - last_backup)) -ge 86400 ] \
      || { [ "$marker" != "$today" ] && time_reached "$now_hm"; }; then
    if sh "$script_dir/backup.sh"; then
      mkdir -p "$STATE_DIR"
      printf '%s\n' "$today" > "$STATE_DIR/daily-marker"
    else
      echo "logical backup failed" >&2
    fi
  fi

  last_base=$(state_get base-success-epoch)
  if [ "$last_base" -eq 0 ] || [ $((now_epoch - last_base)) -ge 604800 ]; then
    if ! sh "$script_dir/base-backup.sh"; then
      echo "base backup failed" >&2
    fi
  fi

  if ! sh "$script_dir/ship-wal.sh"; then
    echo "WAL ship failed" >&2
  fi

  last_drill=$(state_get drill-success-epoch)
  last_backup=$(state_get backup-success-epoch)
  if [ "$last_backup" -ne 0 ] && { [ "$last_drill" -eq 0 ] || [ $((now_epoch - last_drill)) -ge 604800 ]; }; then
    if ! sh "$script_dir/verify-restore.sh"; then
      echo "restore drill failed" >&2
    fi
  fi

  sleep "$interval"
done
