#!/bin/sh
# Restore the newest logical backup into a throwaway database, check it, then drop it.
set -eu
set -o pipefail
script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
. "$script_dir/lib.sh"
prepare_pgpass

set -- "$BACKUP_DIR"/daily/*.dump.enc
if [ ! -f "$1" ]; then
  echo "no logical backup to verify" >&2
  exit 1
fi
latest=$1
for candidate; do
  if [ "$candidate" -nt "$latest" ]; then
    latest=$candidate
  fi
done

plain=$(mktemp)
target="utewedding_drill_$(date -u +%s)"
require_safe_name "$target"
if [ "$target" = "$PGDATABASE" ]; then
  echo "refusing to drill over the live database" >&2
  exit 1
fi
cleanup() {
  rm -f "$plain"
  dropdb --if-exists "$target" >/dev/null 2>&1 || true
}
trap cleanup EXIT

decrypt_file "$latest" "$plain"
dropdb --if-exists "$target" >/dev/null 2>&1 || true
createdb "$target"
pg_restore --no-owner --no-privileges --exit-on-error --dbname="$target" "$plain"
psql --dbname="$target" --set=ON_ERROR_STOP=1 <<'SQL'
SELECT COUNT(*) AS flyway_rows FROM flyway_schema_history;
SELECT to_regclass('public.orders') IS NOT NULL AS orders_present;
SELECT to_regclass('public.payments') IS NOT NULL AS payments_present;
SELECT to_regclass('public.users') IS NOT NULL AS users_present;
SQL
psql --dbname="$target" --set=ON_ERROR_STOP=1 --tuples-only --no-align <<'SQL' | grep -qx t
SELECT to_regclass('public.orders') IS NOT NULL
   AND to_regclass('public.payments') IS NOT NULL
   AND to_regclass('public.users') IS NOT NULL
   AND (SELECT COUNT(*) FROM flyway_schema_history) > 0;
SQL
state_put drill-success-epoch "$(date -u +%s)"
state_put drill-backup "$(basename "$latest")"
render_metrics
echo "restore drill succeeded from $(basename "$latest")"
