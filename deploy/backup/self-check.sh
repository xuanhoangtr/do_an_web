#!/bin/sh
# Syntax-check the backup scripts. This does not connect to a database.
set -eu
dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
for script in "$dir"/*.sh; do
  sh -n "$script"
done
if grep -nE 'PGPASSWORD=[^$:{]"' "$dir"/*.sh >/dev/null; then
  echo "refusing a quoted PGPASSWORD literal" >&2
  exit 1
fi
if grep -nE 'BEGIN [A-Z ]*PRIVATE KEY' "$dir"/*.sh >/dev/null; then
  echo "refusing an embedded private key" >&2
  exit 1
fi
echo "backup scripts passed syntax check"
