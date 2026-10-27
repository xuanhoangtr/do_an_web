#!/bin/sh
# PostgreSQL archive_command. Copies one completed WAL segment and fsyncs it.
# Usage: archive-wal.sh %p %f
set -eu

src=${1:?missing source path}
name=${2:?missing wal name}
dest_dir=${WAL_ARCHIVE_DIR:-/var/lib/postgresql/wal-archive}

case "$name" in
  *[!A-Za-z0-9._-]*)
    echo "refusing unsafe WAL name" >&2
    exit 1
    ;;
  *.tmp)
    echo "refusing temporary WAL name" >&2
    exit 1
    ;;
esac

mkdir -p "$dest_dir"
dest="$dest_dir/$name"
tmp="$dest_dir/$name.tmp.$$"

if [ -f "$dest" ]; then
  exit 0
fi

if ! dd if="$src" of="$tmp" bs=1048576 conv=fsync status=none; then
  cp "$src" "$tmp"
  sync
fi
mv "$tmp" "$dest"
sync
exit 0
