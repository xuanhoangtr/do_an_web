#!/bin/sh
# Weekly physical base backup. WAL segments needed after this point are shipped separately.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
. "$script_dir/lib.sh"
prepare_pgpass

stamp=$(date -u +%Y%m%dT%H%M%SZ)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
pg_basebackup --dbname="$PGDATABASE" --format=tar --gzip --wal-method=fetch \
  --pgdata="$work" --no-password --progress
dest="$BACKUP_DIR/base/$stamp"
mkdir -p "$dest"
status=0
for plain in "$work"/*.tar.gz; do
  [ -f "$plain" ] || continue
  enc="$dest/$(basename "$plain").enc"
  encrypt_file "$plain" "$enc"
  ship_file "$enc" "base/$stamp/$(basename "$enc")" || status=$?
done
rm -rf "$work"
trap - EXIT
prune_days "$BACKUP_DIR/base" "${BASE_RETENTION_DAYS:-21}"
if [ -d "$OFFSITE_DIR/base" ]; then
  prune_days "$OFFSITE_DIR/base" "${BASE_RETENTION_DAYS:-21}"
fi
if [ "$status" -eq 0 ]; then
  state_put base-success-epoch "$(date -u +%s)"
fi
render_metrics
exit "$status"
