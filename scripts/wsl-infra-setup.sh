#!/usr/bin/env bash
# One-time local infrastructure for CodeLens AI without Docker (WSL Ubuntu):
#   * PostgreSQL + pgvector on port 5433 (5433 so it never clashes with a native Windows Postgres on 5432)
#   * Redis on 6379 with volatile-lru eviction (streams / budget keys must never be evicted)
# WSL2 forwards localhost, so the Windows-side backend reaches both at localhost.
#
# Run from Windows:   wsl -d Ubuntu -- sudo bash scripts/wsl-infra-setup.sh
set -euo pipefail

PGPORT="${PGPORT:-5433}"
PGPASSWORD_VALUE="${PGPASSWORD_VALUE:-postgres}"

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y postgresql redis-server
PGVER="$(ls /usr/lib/postgresql | sort -V | tail -1)"
apt-get install -y "postgresql-${PGVER}-pgvector"

CONF="/etc/postgresql/${PGVER}/main/postgresql.conf"
sed -i -E "s/^#?port = [0-9]+/port = ${PGPORT}/" "$CONF"
sed -i -E "s/^#?listen_addresses = .*/listen_addresses = 'localhost'/" "$CONF"
service postgresql restart

sudo -u postgres psql -p "$PGPORT" -c "ALTER USER postgres PASSWORD '${PGPASSWORD_VALUE}';"
for db in codelensai codelensai_it; do
  if ! sudo -u postgres psql -p "$PGPORT" -tAc "SELECT 1 FROM pg_database WHERE datname='${db}'" | grep -q 1; then
    sudo -u postgres createdb -p "$PGPORT" "$db"
  fi
  # CREATE EXTENSION needs superuser on a self-managed server; do it once here.
  sudo -u postgres psql -p "$PGPORT" -d "$db" -c "CREATE EXTENSION IF NOT EXISTS vector;"
done

REDIS_CONF=/etc/redis/redis.conf
if grep -qE '^#? ?maxmemory-policy' "$REDIS_CONF"; then
  sed -i -E 's/^#? ?maxmemory-policy .*/maxmemory-policy volatile-lru/' "$REDIS_CONF"
else
  echo 'maxmemory-policy volatile-lru' >> "$REDIS_CONF"
fi
service redis-server restart

echo
echo "PostgreSQL ${PGVER} + pgvector on localhost:${PGPORT} (databases: codelensai, codelensai_it; user postgres/${PGPASSWORD_VALUE})"
echo "Redis on localhost:6379"
redis-cli ping
sudo -u postgres psql -p "$PGPORT" -d codelensai -tAc "SELECT 'pgvector ' || extversion FROM pg_extension WHERE extname='vector';"
