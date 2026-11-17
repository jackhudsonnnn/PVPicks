#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
settings="$root/infra/compose/.env.lan"
example="$root/infra/compose/.env.lan.example"
secret="$root/infra/compose/.env.lan.auth"
retry_secret="$root/infra/compose/.env.lan.idempotency"

# Creates the LAN watch party's settings (`.env.lan`, from `.env.lan.example` with a random database password) and
# its own session-signing and retry-fingerprint keys, separate from dev's. Existing files are kept. Values are never
# printed. See docs/site/lan.md.
if [[ $# != 1 || "$1" != "--init" ]]; then
    echo "Usage: $0 --init" >&2
    exit 2
fi

umask 077
for file in "$settings" "$secret" "$retry_secret"; do
    if [[ -L "$file" || ( -e "$file" && ! -f "$file" ) ]]; then
        echo "Refusing non-regular credential file: $file" >&2
        exit 1
    fi
done

# Writes "<name>=<value>" to a new owner-only file atomically, unless the file exists.
write_new() {
    local file="$1" content="$2" tmp
    [[ -e "$file" ]] && return 0
    tmp="$(mktemp "$file.XXXXXXXX")"
    printf '%s\n' "$content" > "$tmp"
    ln "$tmp" "$file"
    rm -f "$tmp"
}

if [[ ! -e "$settings" ]]; then
    password="$(openssl rand -hex 24)"
    write_new "$settings" "$(sed "s/^PVPICKS_DB_PASSWORD=.*/PVPICKS_DB_PASSWORD=$password/" "$example")"
    echo "Created infra/compose/.env.lan: set PVPICKS_LAN_HOST, PVPICKS_BOOTSTRAP_ADMIN and the API key there."
fi
write_new "$secret" "PVPICKS_DEV_JWT_SECRET=$(openssl rand -base64 32)"
write_new "$retry_secret" "PVPICKS_DEV_IDEMPOTENCY_SECRET=$(openssl rand -base64 32)"

for file in "$settings" "$secret" "$retry_secret"; do
    if [[ "$(uname -s)" == "Darwin" ]]; then
        permissions="$(stat -f '%Lp' "$file")"
    else
        permissions="$(stat -c '%a' "$file")"
    fi
    if [[ ! -O "$file" || ( "$permissions" != "600" && "$permissions" != "400" ) ]]; then
        echo "Credential file must be owned by the current user and accessible only to its owner: $file" >&2
        exit 1
    fi
done

echo "LAN settings and keys are ready in ignored, owner-only files under infra/compose/."
