#!/usr/bin/env bash
# Backs up the SecondBrain library: the PostgreSQL database and the stored originals, in one
# dated archive under ./backups. Run from the repository root while PostgreSQL is running.
#
#   scripts/backup.sh [storage-directory]        (default ./data/documents)
#
# The database is dumped through its own container, so no password is read or printed. For a
# fully consistent copy, stop the application first (Ctrl+C); a running app keeps working, but a
# document uploaded during the backup may be missing from it.
set -euo pipefail
umask 077

storage=${1:-./data/documents}
container=${SECONDBRAIN_DB_CONTAINER:-$(docker compose ps -q postgres 2>/dev/null || true)}
if [ -z "$container" ]; then
  echo "PostgreSQL is not running. Start it with: docker compose up -d --wait postgres" >&2
  exit 1
fi
if [ ! -d "$storage" ]; then
  echo "Storage directory not found: $storage" >&2
  exit 1
fi
if lsof -iTCP:"${SECONDBRAIN_PORT:-8080}" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Note: SecondBrain is running; uploads during the backup may be missing from it." >&2
fi

stamp=$(date +%Y%m%d-%H%M%S)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/secondbrain-$stamp"

docker exec "$container" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' \
  > "$work/secondbrain-$stamp/database.dump"
cp -Rp "$storage" "$work/secondbrain-$stamp/documents"
{
  echo "created=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "documents=$(find "$work/secondbrain-$stamp/documents" -type f -name '*.original' | wc -l | tr -d ' ')"
  echo "database_bytes=$(wc -c < "$work/secondbrain-$stamp/database.dump" | tr -d ' ')"
} > "$work/secondbrain-$stamp/manifest.txt"

mkdir -p backups
archive="backups/secondbrain-$stamp.tar.gz"
tar -czf "$archive" -C "$work" "secondbrain-$stamp"
echo "Backup written to $archive"
