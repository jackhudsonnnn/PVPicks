#!/usr/bin/env bash
set -euo pipefail

# Creates LAN watch party player accounts and prints each one's single-use setup link (docs/site/lan.md, Runbook).
# Signs in as the admin (password prompted, never echoed or stored). With `qrencode` installed, each link is also
# drawn as a QR code in the terminal for guests to scan. Links work once, for 24 hours; issue a new one from the admin
# Players tab if a guest misses theirs. Existing usernames are reported and skipped.
#
#   scripts/lan-players.sh p2picks.example.com owner alice bob carol

if [[ $# -lt 3 ]]; then
    echo "Usage: $0 <lan-host> <admin-username> <player> [player...]" >&2
    exit 2
fi
host="$1"
admin="$2"
shift 2
base="https://$host"
jar="$(mktemp)"
trap 'rm -f "$jar"' EXIT
chmod 600 "$jar"

json_field() {
    python3 -c 'import json, sys; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$1"
}

csrf() {
    curl -fsS -c "$jar" -b "$jar" "$base/api/auth/csrf" | json_field token
}

if ! csrf > /dev/null; then
    echo "Can't reach $base (is the stack up, and does this machine trust its certificate?)." >&2
    exit 1
fi

read -r -s -p "Password for admin $admin: " password
echo
login_body="$(python3 -c 'import json, sys; print(json.dumps({"username": sys.argv[1], "password": sys.argv[2]}))' \
    "$admin" "$password")"
unset password
status="$(curl -sS -o /dev/null -w '%{http_code}' -c "$jar" -b "$jar" -H "Origin: $base" \
    -H "X-CSRF-TOKEN: $(csrf)" -H "Content-Type: application/json" --data-binary @- "$base/api/auth/login" \
    <<< "$login_body")"
unset login_body
if [[ "$status" != "204" ]]; then
    echo "Admin sign-in failed (HTTP $status)." >&2
    exit 1
fi

for player in "$@"; do
    body="$(python3 -c 'import json, sys; print(json.dumps({"username": sys.argv[1]}))' "$player")"
    response="$(curl -sS -w '\n%{http_code}' -c "$jar" -b "$jar" -H "Origin: $base" -H "X-CSRF-TOKEN: $(csrf)" \
        -H "Content-Type: application/json" -d "$body" "$base/api/admin/players")"
    code="${response##*$'\n'}"
    payload="${response%$'\n'*}"
    case "$code" in
        201)
            link="$base/setup#$(json_field setupCode <<< "$payload")"
            echo "== $(json_field username <<< "$payload")"
            echo "$link"
            if command -v qrencode > /dev/null; then qrencode -t ansiutf8 "$link"; fi
            ;;
        409) echo "== $player: username taken; issue a link from the admin Players tab if needed." ;;
        *) echo "== $player: failed (HTTP $code): $payload" >&2 ;;
    esac
done
