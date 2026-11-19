#!/usr/bin/env bash
# Runs deploy/install.sh against a copy of the Mikrus setup on this machine (needs Docker and root):
# a Caddy container "pointsapp-caddy-1" on the network "pointsapp_default", holding port 443 with an
# Origin-style certificate for *.pointsapp.pl and a pointsapp site. Then checks that:
#   - pointsapp still answers, and kc.pointsapp.pl serves the game through Caddy;
#   - a second install is idempotent (one block in the Caddyfile);
#   - a broken block is refused and the Caddyfile comes back byte for byte.
# Usage, after mvn package and npm run build:   sudo deploy/test-install.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
S="$(mktemp -d)"
cleanup() { docker rm -f pointsapp-caddy-1 kingscastles >/dev/null 2>&1 || true; docker network rm pointsapp_default >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

mkdir -p "$S/pa/certs" "$S/rel/release"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=pointsapp.pl" \
  -addext "subjectAltName=DNS:*.pointsapp.pl,DNS:pointsapp.pl" \
  -keyout "$S/pa/certs/origin.key" -out "$S/pa/certs/origin.crt" 2>/dev/null
chmod 644 "$S/pa/certs/origin.key"
printf 'pointsapp.pl, www.pointsapp.pl {\n  tls /certs/origin.crt /certs/origin.key\n  respond "pointsapp ok"\n}\n' > "$S/pa/Caddyfile"
docker network create pointsapp_default >/dev/null
docker run -d --name pointsapp-caddy-1 --network pointsapp_default -p 127.0.0.1:443:443 \
  -v "$S/pa/Caddyfile:/etc/caddy/Caddyfile" -v "$S/pa/certs:/certs" caddy:2 >/dev/null
sleep 3

cp "$ROOT/deploy/install.sh" "$ROOT/deploy/Caddyfile" "$S/rel/"
cp "$ROOT"/server/target/server-*.jar "$S/rel/release/server.jar"
cp -r "$ROOT/client/dist" "$S/rel/release/client"

get() { curl -fsSk --noproxy '*' --resolve "$1:443:127.0.0.1" "https://$1$2"; }
ok() { echo "ok: $*"; }

KC_CADDYFILE="$S/pa/Caddyfile" bash "$S/rel/install.sh"
[ "$(get pointsapp.pl /)" = "pointsapp ok" ] && ok "pointsapp still answers"
get kc.pointsapp.pl /api/rules | grep -q buildings && ok "the game API answers through Caddy"
get kc.pointsapp.pl / | grep -q '<html' && ok "the game page answers through Caddy"

KC_CADDYFILE="$S/pa/Caddyfile" bash "$S/rel/install.sh" >/dev/null
[ "$(grep -c 'BEGIN kings-and-castles' "$S/pa/Caddyfile")" = 1 ] && ok "a second install keeps one block"

cp "$S/pa/Caddyfile" "$S/good"
sed -i 's/encode gzip/bogus_directive on/' "$S/rel/Caddyfile"
if KC_CADDYFILE="$S/pa/Caddyfile" bash "$S/rel/install.sh" >/dev/null 2>&1; then
  echo "a broken block was accepted" >&2; exit 1
fi
cmp -s "$S/good" "$S/pa/Caddyfile" && ok "a broken block is refused and the Caddyfile comes back unchanged"
[ "$(get pointsapp.pl /)" = "pointsapp ok" ] && ok "pointsapp still answers after the refused change"
get kc.pointsapp.pl /api/rules | grep -q buildings && ok "the game still answers after the refused change"
