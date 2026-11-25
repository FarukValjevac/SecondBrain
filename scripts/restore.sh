#!/usr/bin/env bash
# Restores a backup made by scripts/backup.sh into an empty setup: the database and the stored
# originals. Run from the repository root with PostgreSQL running and SecondBrain stopped.
#
#   scripts/restore.sh <archive> [storage-directory] [--replace]
#
# Without --replace it refuses to overwrite a database that already holds documents or a storage
# directory that is not empty. With --replace the current library is deleted first.
set -euo pipefail
umask 077

archive=${1:?Usage: scripts/restore.sh <archive> [storage-directory] [--replace]}
storage=./data/documents
replace=false
for argument in "${@:2}"; do
  if [ "$argument" = --replace ]; then replace=true; else storage=$argument; fi
done
container=${SECONDBRAIN_DB_CONTAINER:-$(docker compose ps -q postgres 2>/dev/null || true)}
if [ -z "$container" ]; then
  echo "PostgreSQL is not running. Start it with: docker compose up -d --wait postgres" >&2
  exit 1
fi
if lsof -iTCP:"${SECONDBRAIN_PORT:-8080}" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Stop SecondBrain first (Ctrl+C), then restore." >&2
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
tar -xzf "$archive" -C "$work"
source_dir=$(find "$work" -mindepth 1 -maxdepth 1 -type d -name 'secondbrain-*' | head -1)
if [ -z "$source_dir" ] || [ ! -f "$source_dir/database.dump" ] || [ ! -d "$source_dir/documents" ]; then
  echo "This is not a SecondBrain backup archive." >&2
  exit 1
fi

existing=$(docker exec "$container" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT to_regclass('"'"'documents'"'"') IS NOT NULL AND EXISTS (SELECT 1 FROM documents)"' 2>/dev/null || echo f)
if [ "$replace" != true ]; then
  if [ "$existing" = t ]; then
    echo "The database already holds documents. Use --replace to delete them and restore." >&2
    exit 1
  fi
  if [ -d "$storage" ] && [ -n "$(ls -A "$storage" 2>/dev/null)" ]; then
    echo "$storage is not empty. Use --replace to delete it and restore." >&2
    exit 1
  fi
fi

# Recreate the database from the dump (schema, Flyway history and all rows).
docker exec "$container" sh -c 'dropdb -U "$POSTGRES_USER" --if-exists --force "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" "$POSTGRES_DB"'
docker exec -i "$container" sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --exit-on-error' \
  < "$source_dir/database.dump"

rm -rf "$storage"
mkdir -p "$(dirname "$storage")"
cp -Rp "$source_dir/documents" "$storage"
chmod 700 "$storage"
echo "Restored $(grep '^documents=' "$source_dir/manifest.txt" | cut -d= -f2) originals and the database. Start SecondBrain as usual."
