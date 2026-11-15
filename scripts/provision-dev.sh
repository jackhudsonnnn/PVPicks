#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
settings="$root/infra/compose/.env"
example="$root/infra/compose/.env.example"
secret="$root/infra/compose/.env.auth"
retry_secret="$root/infra/compose/.env.idempotency"

# Creates the local settings (`.env`, from `.env.example` with a random database password) and the dev
# session-signing and retry-fingerprint keys. Existing files are kept. Values are never printed. Accounts need no
# provisioning: the dev backend seeds the demo world and signs you in without a password (docs/site/demo-data.md).
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

if [[ ! -e "$settings" ]]; then
    tmp="$(mktemp "$root/infra/compose/.env.XXXXXXXX")"
    trap 'rm -f "$tmp"' EXIT
    password="$(openssl rand -hex 24)"
    sed "s/^PVPICKS_DB_PASSWORD=.*/PVPICKS_DB_PASSWORD=$password/" "$example" > "$tmp"
    ln "$tmp" "$settings"
    rm "$tmp"
    trap - EXIT
    echo "Created infra/compose/.env (add PVPICKS_BALLDONTLIE_API_KEY there for live scores)."
fi

if [[ ! -e "$secret" ]]; then
    tmp="$(mktemp "$root/infra/compose/.env.auth.XXXXXXXX")"
    trap 'rm -f "$tmp"' EXIT
    value="$(openssl rand -base64 32)"
    printf 'PVPICKS_DEV_JWT_SECRET=%s\n' "$value" > "$tmp"
    ln "$tmp" "$secret"
    rm "$tmp"
    trap - EXIT
fi

if [[ ! -e "$retry_secret" ]]; then
    tmp="$(mktemp "$root/infra/compose/.env.idempotency.XXXXXXXX")"
    trap 'rm -f "$tmp"' EXIT
    value="$(openssl rand -base64 32)"
    printf 'PVPICKS_DEV_IDEMPOTENCY_SECRET=%s\n' "$value" > "$tmp"
    ln "$tmp" "$retry_secret"
    rm "$tmp"
    trap - EXIT
fi

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

echo "Local settings and dev keys are ready in ignored, owner-only files under infra/compose/."
