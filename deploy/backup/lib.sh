#!/bin/sh
# Shared backup helpers. Credentials come from the environment or a passphrase file.
set -eu

umask 077

: "${PGHOST:?Set PGHOST}"
: "${PGPORT:=5432}"
: "${PGUSER:?Set PGUSER}"
: "${PGDATABASE:?Set PGDATABASE}"

BACKUP_DIR=${BACKUP_DIR:-/backups}
WAL_ARCHIVE_DIR=${WAL_ARCHIVE_DIR:-/wal}
OFFSITE_DIR=${OFFSITE_DIR:-/offsite}
METRICS_DIR=${METRICS_DIR:-/metrics}
STATE_DIR="$BACKUP_DIR/state"
OPENSSL_ITER=${OPENSSL_ITER:-200000}

require_safe_name() {
  case "$1" in
    ""|*[!A-Za-z0-9_]*)
      echo "unsafe database identifier: $1" >&2
      exit 1
      ;;
  esac
}

passphrase_file() {
  if [ -n "${BACKUP_ENCRYPTION_PASSPHRASE_FILE:-}" ]; then
    if [ ! -f "$BACKUP_ENCRYPTION_PASSPHRASE_FILE" ]; then
      echo "BACKUP_ENCRYPTION_PASSPHRASE_FILE is missing" >&2
      exit 1
    fi
    printf '%s' "$BACKUP_ENCRYPTION_PASSPHRASE_FILE"
    return
  fi
  if [ -z "${BACKUP_ENCRYPTION_PASSPHRASE:-}" ]; then
    echo "Set BACKUP_ENCRYPTION_PASSPHRASE or BACKUP_ENCRYPTION_PASSPHRASE_FILE" >&2
    exit 1
  fi
  file=/tmp/utewedding-backup-passphrase
  umask 077
  printf '%s' "$BACKUP_ENCRYPTION_PASSPHRASE" > "$file"
  chmod 600 "$file"
  printf '%s' "$file"
}

prepare_pgpass() {
  require_safe_name "$PGDATABASE"
  file=${PGPASSFILE:-/tmp/utewedding.pgpass}
  umask 077
  host=$(printf '%s' "$PGHOST" | sed 's/\\/\\\\/g; s/:/\\:/g')
  port=$(printf '%s' "$PGPORT" | sed 's/\\/\\\\/g; s/:/\\:/g')
  user=$(printf '%s' "$PGUSER" | sed 's/\\/\\\\/g; s/:/\\:/g')
  password=$(printf '%s' "${PGPASSWORD:-}" | sed 's/\\/\\\\/g; s/:/\\:/g')
  if [ -z "${PGPASSWORD:-}" ] && [ -z "${PGPASSFILE:-}" ]; then
    echo "Set PGPASSWORD or PGPASSFILE" >&2
    exit 1
  fi
  if [ -n "${PGPASSWORD:-}" ]; then
    printf '%s:%s:*:%s:%s\n' "$host" "$port" "$user" "$password" > "$file"
    chmod 600 "$file"
    export PGPASSFILE="$file"
    unset PGPASSWORD
  fi
  export PGCONNECT_TIMEOUT=${PGCONNECT_TIMEOUT:-10}
}

encrypt_file() {
  plain=$1
  enc=$2
  pass=$(passphrase_file)
  openssl enc -aes-256-cbc -pbkdf2 -iter "$OPENSSL_ITER" -md sha256 -salt \
    -pass "file:$pass" -in "$plain" -out "$enc"
  chmod 640 "$enc"
  openssl dgst -sha256 -r "$enc" | awk '{print $1}' > "$enc.sha256"
  chmod 640 "$enc.sha256"
  if openssl dgst -sha256 -mac HMAC -macopt "keyfile:$pass" -r "$enc" > "$enc.hmac.tmp"; then
    awk '{print $1}' "$enc.hmac.tmp" > "$enc.hmac"
    chmod 640 "$enc.hmac"
    rm -f "$enc.hmac.tmp"
  else
    rm -f "$enc.hmac.tmp"
    echo "HMAC failed; refusing to keep an unauthenticated backup" >&2
    rm -f "$enc" "$enc.sha256"
    exit 1
  fi
}

decrypt_file() {
  enc=$1
  plain=$2
  pass=$(passphrase_file)
  expected=$(awk '{print $1}' "$enc.sha256")
  actual=$(openssl dgst -sha256 -r "$enc" | awk '{print $1}')
  if [ "$expected" != "$actual" ]; then
    echo "checksum mismatch for $enc" >&2
    exit 1
  fi
  if [ -f "$enc.hmac" ]; then
    expected_hmac=$(awk '{print $1}' "$enc.hmac")
    actual_hmac=$(openssl dgst -sha256 -mac HMAC -macopt "keyfile:$pass" -r "$enc" | awk '{print $1}')
    if [ "$expected_hmac" != "$actual_hmac" ]; then
      echo "HMAC mismatch for $enc" >&2
      exit 1
    fi
  fi
  openssl enc -d -aes-256-cbc -pbkdf2 -iter "$OPENSSL_ITER" -md sha256 \
    -pass "file:$pass" -in "$enc" -out "$plain"
  chmod 600 "$plain"
}

ship_file() {
  file=$1
  relative=$2
  if [ -n "${BACKUP_OFFSITE_COMMAND:-}" ]; then
    case "$BACKUP_OFFSITE_COMMAND" in
      *[!A-Za-z0-9/_.-]*)
        echo "BACKUP_OFFSITE_COMMAND must be a single executable path" >&2
        return 1
        ;;
    esac
    "$BACKUP_OFFSITE_COMMAND" "$file"
  fi
  if [ -d "$OFFSITE_DIR" ]; then
    mkdir -p "$OFFSITE_DIR/$(dirname "$relative")"
    cp -p "$file" "$OFFSITE_DIR/$relative"
    if [ -f "$file.sha256" ]; then cp -p "$file.sha256" "$OFFSITE_DIR/$relative.sha256"; fi
    if [ -f "$file.hmac" ]; then cp -p "$file.hmac" "$OFFSITE_DIR/$relative.hmac"; fi
    sync
  elif [ "${BACKUP_REQUIRE_OFFSITE:-true}" = "true" ]; then
    echo "offsite directory is missing" >&2
    return 1
  fi
  if [ "${BACKUP_REQUIRE_OFFSITE:-true}" = "true" ] && [ "${BACKUP_OFFSITE_IS_REMOTE:-false}" != "true" ] \
      && [ -z "${BACKUP_OFFSITE_COMMAND:-}" ]; then
    echo "offsite target is not remote; set BACKUP_OFFSITE_IS_REMOTE=true only for another machine or object storage" >&2
    return 2
  fi
  return 0
}

state_put() {
  mkdir -p "$STATE_DIR"
  printf '%s\n' "$2" > "$STATE_DIR/$1"
}

state_get() {
  if [ -f "$STATE_DIR/$1" ]; then
    awk 'NR==1 { print; exit }' "$STATE_DIR/$1"
  else
    printf '0'
  fi
}

render_metrics() {
  mkdir -p "$METRICS_DIR"
  offsite_configured=0
  if [ "${BACKUP_OFFSITE_IS_REMOTE:-false}" = "true" ] || [ -n "${BACKUP_OFFSITE_COMMAND:-}" ]; then
    offsite_configured=1
  fi
  tmp="$METRICS_DIR/backup.prom.tmp.$$"
  cat > "$tmp" <<EOF
# HELP utewedding_backup_last_success_unixtime Unix time of the last encrypted logical backup.
# TYPE utewedding_backup_last_success_unixtime gauge
utewedding_backup_last_success_unixtime $(state_get backup-success-epoch)
# HELP utewedding_backup_offsite_last_success_unixtime Unix time of the last offsite logical backup.
# TYPE utewedding_backup_offsite_last_success_unixtime gauge
utewedding_backup_offsite_last_success_unixtime $(state_get offsite-success-epoch)
# HELP utewedding_backup_offsite_configured 1 when the operator marked the offsite target as remote.
# TYPE utewedding_backup_offsite_configured gauge
utewedding_backup_offsite_configured $offsite_configured
# HELP utewedding_backup_drill_last_success_unixtime Unix time of the last successful restore drill.
# TYPE utewedding_backup_drill_last_success_unixtime gauge
utewedding_backup_drill_last_success_unixtime $(state_get drill-success-epoch)
# HELP utewedding_wal_ship_last_success_unixtime Unix time of the last WAL ship.
# TYPE utewedding_wal_ship_last_success_unixtime gauge
utewedding_wal_ship_last_success_unixtime $(state_get wal-success-epoch)
# HELP utewedding_wal_ship_last_success 1 when the most recent WAL ship succeeded.
# TYPE utewedding_wal_ship_last_success gauge
utewedding_wal_ship_last_success $(state_get wal-success-flag)
EOF
  chmod 644 "$tmp"
  mv "$tmp" "$METRICS_DIR/backup.prom"
}

prune_days() {
  dir=$1
  days=$2
  [ -d "$dir" ] || return 0
  find "$dir" -type f -mtime +"$days" -print -delete
}
