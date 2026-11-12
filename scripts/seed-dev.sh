#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 || "$1" != "--seed" ]]; then
    echo "Usage: bash $0 --seed" >&2
    exit 2
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
docker compose -f "$root/infra/compose/docker-compose.dev.yml" exec -T postgres \
    psql -X -v ON_ERROR_STOP=1 -U p2picks -d p2picks < "$root/backend/dev/dev-seed.sql"
echo "Dev seed completed; existing seed data was not reset."
