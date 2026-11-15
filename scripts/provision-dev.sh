#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
compose="$root/infra/compose/docker-compose.dev.yml"
secret="$root/infra/compose/.env.auth"
retry_secret="$root/infra/compose/.env.idempotency"

# Generates the dev session-signing and retry-fingerprint keys. Accounts need no provisioning: the dev backend seeds
# the demo world and signs you in without a password (docs/site/demo-data.md).
if [[ $# != 1 || "$1" != "--init" ]]; then
    echo "Usage: $0 --init" >&2
    exit 2
fi

umask 077
for file in "$secret" "$retry_secret"; do
    if [[ -L "$file" || ( -e "$file" && ! -f "$file" ) ]]; then
        echo "Refusing non-regular credential file: $file" >&2
        exit 1
    fi
done

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

for file in "$secret" "$retry_secret"; do
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

echo "Dev signing and retry keys are ready in ignored local files."
