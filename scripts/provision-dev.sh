#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
compose="$root/infra/compose/docker-compose.dev.yml"
secret="$root/infra/compose/.env.auth"
retry_secret="$root/infra/compose/.env.idempotency"
accounts="$root/infra/compose/.env.dev-accounts"

if [[ $# != 1 || ( "$1" != "--init" && "$1" != "--provision" ) ]]; then
    echo "Usage: $0 --init | --provision" >&2
    exit 2
fi

umask 077
for file in "$secret" "$retry_secret" "$accounts"; do
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

if [[ ! -e "$accounts" ]]; then
    tmp="$(mktemp "$root/infra/compose/.env.dev-accounts.XXXXXXXX")"
    trap 'rm -f "$tmp"' EXIT
    for entry in "newbie PLAYER 1" "intermediate PLAYER 2" "pro PLAYER 3" "admin ADMIN 4"; do
        read -r username role hue <<< "$entry"
        password="$(openssl rand -hex 24)"
        printf '%s,%s,%s,%s\n' "$username" "$password" "$role" "$hue" >> "$tmp"
    done
    ln "$tmp" "$accounts"
    rm "$tmp"
    trap - EXIT
fi

for file in "$secret" "$retry_secret" "$accounts"; do
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

if [[ "$1" == "--init" ]]; then
    echo "Dev signing, retry and account credentials are ready in ignored local files."
    exit 0
fi

count=0
while IFS=, read -r username password role hue extra || [[ -n "$username$password$role$hue$extra" ]]; do
    if [[ ! "$username" =~ ^[a-z0-9_]{3,15}$ || ! "$password" =~ ^[0-9a-f]{48}$ \
        || ! "$role" =~ ^(PLAYER|ADMIN)$ || ! "$hue" =~ ^[1-5]$ || -n "$extra" ]]; then
        echo "Invalid generated credentials file; no users were provisioned." >&2
        exit 1
    fi
    case "$username:$role:$hue" in
        newbie:PLAYER:1|intermediate:PLAYER:2|pro:PLAYER:3|admin:ADMIN:4) ;;
        *) echo "Unexpected dev account in credentials file; no users were provisioned." >&2; exit 1 ;;
    esac
    count=$((count + 1))
done < "$accounts"
if [[ "$count" -ne 4 ]]; then
    echo "Credentials file must contain four accounts; no users were provisioned." >&2
    exit 1
fi

{
    printf '%s\n' 'BEGIN;' 'CREATE EXTENSION IF NOT EXISTS pgcrypto;' \
        'CREATE TEMP TABLE provision_input (username text PRIMARY KEY, password text, role text, hue smallint) ON COMMIT DROP;' \
        'COPY provision_input (username, password, role, hue) FROM STDIN;'
    while IFS=, read -r username password role hue extra || [[ -n "$username$password$role$hue$extra" ]]; do
        printf '%s\t%s\t%s\t%s\n' "$username" "$password" "$role" "$hue"
    done < "$accounts"
    printf '%s\n' '\.' \
        "DO \$\$ BEGIN IF EXISTS (SELECT 1 FROM provision_input p JOIN users u ON u.username = p.username::citext WHERE u.status <> 'ACTIVE' OR u.role <> p.role OR u.password_hash <> crypt(p.password, u.password_hash)) THEN RAISE EXCEPTION 'Existing user does not match local credentials'; END IF; END \$\$;" \
        "INSERT INTO users (id, username, hue, password_hash, role, status) SELECT gen_random_uuid(), p.username, p.hue, crypt(p.password, gen_salt('bf', 12)), p.role, 'ACTIVE' FROM provision_input p WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.username = p.username::citext);" \
        'COMMIT;'
} | docker compose -f "$compose" exec -T postgres psql -X -v ON_ERROR_STOP=1 -U p2picks -d p2picks

echo "Dev accounts provisioned. Credentials remain in the ignored local file."
