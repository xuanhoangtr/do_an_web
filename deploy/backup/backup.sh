#!/bin/sh
# Encrypted logical pg_dump. Plaintext exists only in a temp file and is removed on exit.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
. "$script_dir/lib.sh"
prepare_pgpass

mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/monthly" "$STATE_DIR"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
plain=$(mktemp)
trap 'rm -f "$plain"' EXIT
pg_dump --format=custom --no-owner --no-privileges --file="$plain" --dbname="$PGDATABASE"
name="${PGDATABASE}-logical-${stamp}.dump.enc"
enc="$BACKUP_DIR/daily/$name"
encrypt_file "$plain" "$enc"
rm -f "$plain"
trap - EXIT

state_put backup-success-epoch "$(date -u +%s)"
copy_class() {
  class=$1
  mkdir -p "$BACKUP_DIR/$class" || return 1
  cp "$enc" "$BACKUP_DIR/$class/$name" || return 1
  cp "$enc.sha256" "$BACKUP_DIR/$class/$name.sha256" || return 1
  cp "$enc.hmac" "$BACKUP_DIR/$class/$name.hmac" || return 1
  ship_file "$BACKUP_DIR/$class/$name" "$class/$name"
}

ship_status=0
ship_file "$enc" "daily/$name" || ship_status=$?
if [ "$(date -u +%u)" = "7" ]; then
  copy_class weekly || ship_status=$?
fi
if [ "$(date -u +%d)" = "01" ]; then
  copy_class monthly || ship_status=$?
fi

prune_days "$BACKUP_DIR/daily" "${BACKUP_RETENTION_DAILY_DAYS:-14}"
prune_days "$BACKUP_DIR/weekly" "${BACKUP_RETENTION_WEEKLY_DAYS:-56}"
prune_days "$BACKUP_DIR/monthly" "${BACKUP_RETENTION_MONTHLY_DAYS:-180}"
if [ -d "$OFFSITE_DIR" ]; then
  prune_days "$OFFSITE_DIR/daily" "${BACKUP_RETENTION_DAILY_DAYS:-14}"
  prune_days "$OFFSITE_DIR/weekly" "${BACKUP_RETENTION_WEEKLY_DAYS:-56}"
  prune_days "$OFFSITE_DIR/monthly" "${BACKUP_RETENTION_MONTHLY_DAYS:-180}"
fi
if [ "$ship_status" -eq 0 ]; then
  state_put offsite-success-epoch "$(date -u +%s)"
fi
render_metrics
exit "$ship_status"
