#!/bin/sh
# Restore one encrypted logical backup into RESTORE_TARGET_DATABASE.
# The live PGDATABASE is not dropped unless RESTORE_ALLOW_LIVE=true and the app is stopped.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
. "$script_dir/lib.sh"
prepare_pgpass

backup=${1:-}
if [ -z "$backup" ]; then
  set -- "$BACKUP_DIR"/daily/*.dump.enc
  if [ ! -f "$1" ]; then
    echo "usage: restore-logical.sh /backups/daily/file.dump.enc" >&2
    exit 1
  fi
  backup=$1
  for candidate; do
    if [ "$candidate" -nt "$backup" ]; then backup=$candidate; fi
  done
fi
target=${RESTORE_TARGET_DATABASE:?Set RESTORE_TARGET_DATABASE}
require_safe_name "$target"
require_safe_name "$PGDATABASE"
if [ "${RESTORE_CONFIRM:-}" != "$PGDATABASE" ]; then
  echo "Set RESTORE_CONFIRM to the live database name ($PGDATABASE) before restoring." >&2
  exit 1
fi
if [ "$target" = "$PGDATABASE" ] && [ "${RESTORE_ALLOW_LIVE:-false}" != "true" ]; then
  echo "Refusing to restore over the live database. Stop the app and set RESTORE_ALLOW_LIVE=true, or choose another RESTORE_TARGET_DATABASE." >&2
  exit 1
fi

exists=$(psql --dbname=postgres --tuples-only --no-align --set=ON_ERROR_STOP=1 \
  --command "SELECT 1 FROM pg_database WHERE datname = '$target'")
if [ "$exists" = "1" ] && [ "${RESTORE_REPLACE_TARGET:-false}" != "true" ]; then
  echo "Target database already exists. Set RESTORE_REPLACE_TARGET=true to replace it." >&2
  exit 1
fi

plain=$(mktemp)
trap 'rm -f "$plain"' EXIT
decrypt_file "$backup" "$plain"
if [ "$exists" = "1" ]; then
  dropdb "$target"
fi
createdb "$target"
pg_restore --no-owner --no-privileges --exit-on-error --dbname="$target" "$plain"
echo "restored $(basename "$backup") into $target"
