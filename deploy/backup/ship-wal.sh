#!/bin/sh
# Encrypt archived WAL and copy it offsite. Plaintext is removed only after that succeeds.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
. "$script_dir/lib.sh"

mkdir -p "$BACKUP_DIR/wal" "$STATE_DIR"
status=0
shipped=0
for plain in "$WAL_ARCHIVE_DIR"/*; do
  [ -f "$plain" ] || continue
  name=$(basename "$plain")
  case "$name" in
    *[!A-Za-z0-9._-]*|*.tmp|*.partial) continue ;;
  esac
  enc="$BACKUP_DIR/wal/$name.enc"
  if [ ! -f "$enc" ] || [ ! -f "$enc.sha256" ] || [ ! -f "$enc.hmac" ]; then
    if ! encrypt_file "$plain" "$enc"; then
      status=1
      continue
    fi
  fi
  if ship_file "$enc" "wal/$name.enc"; then
    rm -f "$plain"
    shipped=$((shipped + 1))
  else
    status=$?
  fi
done
prune_days "$BACKUP_DIR/wal" "${WAL_RETENTION_DAYS:-21}"
if [ -d "$OFFSITE_DIR/wal" ]; then
  prune_days "$OFFSITE_DIR/wal" "${WAL_RETENTION_DAYS:-21}"
fi
if [ "$status" -eq 0 ]; then
  state_put wal-success-epoch "$(date -u +%s)"
  state_put wal-success-flag 1
else
  state_put wal-success-flag 0
fi
render_metrics
echo "shipped_or_confirmed=$shipped status=$status"
exit "$status"
