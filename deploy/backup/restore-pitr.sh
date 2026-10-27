#!/bin/sh
# Prepare a PITR data directory from an encrypted base backup and encrypted WAL.
# This does not modify the live data directory. Start the printed container only
# after the live app and database are stopped, and only on a new volume.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
. "$script_dir/lib.sh"

base_dir=${BASE_BACKUP_DIR:?Set BASE_BACKUP_DIR to a directory containing base.tar.gz.enc}
wal_dir=${WAL_SOURCE_DIR:-$BACKUP_DIR/wal}
target_time=${RESTORE_TARGET_TIME:-}
case "$target_time" in
  "") ;;
  [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]" "[0-9][0-9]:[0-9][0-9]:[0-9][0-9]) ;;
  *)
    echo "RESTORE_TARGET_TIME must be empty or YYYY-MM-DD HH:MM:SS" >&2
    exit 1
    ;;
esac
case "$base_dir" in
  /var/lib/postgresql/data*)
    echo "refusing to use the live data directory as a base backup" >&2
    exit 1
    ;;
esac

work=${RESTORE_WORK_DIR:-$BACKUP_DIR/restore-work/$(date -u +%Y%m%dT%H%M%SZ)}
case "$work" in
  /var/lib/postgresql/data*)
    echo "refusing to write PITR output into the live data directory" >&2
    exit 1
    ;;
esac
rm -rf "$work"
mkdir -p "$work/data/pg_wal" "$work/encrypted-wal" "$work/bin" "$work/plain"

for enc in "$base_dir"/*.enc; do
  [ -f "$enc" ] || continue
  decrypt_file "$enc" "$work/plain/$(basename "$enc" .enc)"
done
if [ ! -f "$work/plain/base.tar.gz" ]; then
  echo "base.tar.gz.enc was not found in $base_dir" >&2
  exit 1
fi
tar -xzf "$work/plain/base.tar.gz" -C "$work/data"
if [ -f "$work/plain/pg_wal.tar.gz" ]; then
  tar -xzf "$work/plain/pg_wal.tar.gz" -C "$work/data/pg_wal"
fi
rm -rf "$work/plain"

for enc in "$wal_dir"/*.enc; do
  [ -f "$enc" ] || continue
  cp "$enc" "$work/encrypted-wal/"
  [ -f "$enc.sha256" ] && cp "$enc.sha256" "$work/encrypted-wal/"
  [ -f "$enc.hmac" ] && cp "$enc.hmac" "$work/encrypted-wal/"
done

cat > "$work/bin/restore-wal.sh" <<EOF
#!/bin/sh
set -eu
name=\$1
dest=\$2
case "\$name" in
  *[!A-Za-z0-9._-]*) exit 1 ;;
esac
src="/encrypted-wal/\$name.enc"
[ -f "\$src" ] || exit 1
openssl enc -d -aes-256-cbc -pbkdf2 -iter ${OPENSSL_ITER} -md sha256 \\
  -pass file:/run/secrets/backup_passphrase -in "\$src" -out "\$dest"
EOF
chmod 755 "$work/bin/restore-wal.sh"

cat > "$work/data/postgresql.auto.conf" <<EOF
restore_command = '/bin/sh /restore/restore-wal.sh %f %p'
recovery_target_action = 'promote'
EOF
if [ -n "$target_time" ]; then
  printf "recovery_target_time = '%s'\n" "$target_time" >> "$work/data/postgresql.auto.conf"
fi
: > "$work/data/recovery.signal"
chmod 700 "$work/data"

printf '%s\n' "PITR directory prepared at: $work/data"
printf '%s\n' "Stop the live app before starting this copy. Do not mount the live data volume."
printf '%s\n' "Pass the passphrase file with BACKUP_ENCRYPTION_PASSPHRASE_FILE. Example:"
cat <<EOF
docker run --rm --name utewedding-pitr \\
  --entrypoint docker-entrypoint.sh \\
  -p 127.0.0.1:5433:5432 \\
  -v "$work/data":/var/lib/postgresql/data \\
  -v "$work/encrypted-wal":/encrypted-wal:ro \\
  -v "$work/bin/restore-wal.sh":/restore/restore-wal.sh:ro \\
  -v "\$BACKUP_ENCRYPTION_PASSPHRASE_FILE":/run/secrets/backup_passphrase:ro \\
  utewedding-backup:16 postgres
EOF
printf '%s\n' "After promotion, check orders, payments, users, and flyway_schema_history."
