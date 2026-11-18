#!/usr/bin/env bash
# Installs or updates the Kings & Castles game on the Mikrus server. Run as root.
#
# GitHub Actions (.github/workflows/deploy.yml) builds the game, copies this folder with release/server.jar and
# release/client/ to the server, and runs this script there. Running it again updates the game; saved rooms stay.
#
# The server already runs pointsapp in Docker, with its Caddy holding ports 80 and 443. The game joins that setup
# without touching pointsapp itself:
#   - a container "kingscastles" (Java 21) on the Docker network of pointsapp, with no port open to the outside;
#   - one site block for kc.pointsapp.pl added to pointsapp's Caddyfile between marker lines, checked with
#     "caddy validate" before a graceful reload (the old file comes back if the check fails).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DOMAIN="${KC_DOMAIN:-kc.pointsapp.pl}"
APP=/opt/kingscastles
DATA=/var/lib/kingscastles
IMAGE="${KC_JAVA_IMAGE:-eclipse-temurin:21.0.5_11-jre}"
NETWORK="${KC_NETWORK:-pointsapp_default}"
PROXY="${KC_PROXY_CONTAINER:-pointsapp-caddy-1}"
CADDYFILE="${KC_CADDYFILE:-/home/appuser/pointsapp/Caddyfile}"
# "auto": add the site to the proxy; "manual": only print the block (Tomek adds it himself)
PROXY_MODE="${KC_PROXY_MODE:-auto}"
UID_GID=10001:10001

[ -f "$HERE/release/server.jar" ] && [ -f "$HERE/release/client/index.html" ] \
  || { echo "Missing release/server.jar or release/client/ next to this script" >&2; exit 1; }
command -v docker >/dev/null || { echo "Docker is not installed" >&2; exit 1; }
docker network inspect "$NETWORK" >/dev/null 2>&1 || { echo "Docker network $NETWORK not found" >&2; exit 1; }

echo "== game files"
mkdir -p "$APP" "$DATA"
rm -rf "$APP/client.new"
cp -r "$HERE/release/client" "$APP/client.new"
cp "$HERE/release/server.jar" "$APP/server.jar.new"
mv -f "$APP/server.jar.new" "$APP/server.jar"
rm -rf "$APP/client.old"
[ -d "$APP/client" ] && mv "$APP/client" "$APP/client.old"
mv "$APP/client.new" "$APP/client"
rm -rf "$APP/client.old"
# the game files belong to root and are mounted read-only; only the saved rooms are writable by the game
chown -R root:root "$APP" && chmod -R a+rX "$APP"
chown -R "$UID_GID" "$DATA" && chmod 750 "$DATA"

echo "== container"
docker pull -q "$IMAGE" >/dev/null
docker rm -f kingscastles >/dev/null 2>&1 || true
docker run -d --name kingscastles --restart unless-stopped \
  --network "$NETWORK" \
  --user "$UID_GID" --read-only --tmpfs /tmp:size=32m \
  --cap-drop ALL --security-opt no-new-privileges \
  --memory 400m --pids-limit 200 \
  -v "$APP:/app:ro" -v "$DATA:/data" \
  -e KC_DATA_DIR=/data -e KC_CLIENT_DIR=/app/client -e "KC_ALLOWED_ORIGINS=https://$DOMAIN" \
  "$IMAGE" java -Xmx256m -XX:+UseSerialGC -jar /app/server.jar >/dev/null

for i in $(seq 1 40); do
  if docker exec "$PROXY" wget -qO- "http://kingscastles:8080/api/rules" >/dev/null 2>&1; then
    echo "ok: the game answers inside the Docker network"
    break
  fi
  [ "$i" = 40 ] && { echo "The game did not start:" >&2; docker logs --tail 50 kingscastles >&2; exit 1; }
  sleep 2
done

echo "== proxy (Caddy of pointsapp)"
block="$(sed "s/kc.pointsapp.pl/$DOMAIN/g" "$HERE/Caddyfile")"
if [ "$PROXY_MODE" != auto ]; then
  echo "Add this to $CADDYFILE and reload Caddy:"
  echo "$block"
  exit 0
fi
# the Origin certificate has to cover the game's address
san="$(docker exec "$PROXY" cat /certs/origin.crt | openssl x509 -noout -ext subjectAltName 2>/dev/null || true)"
re_domain="${DOMAIN//./\\.}"
re_parent="\\*\\.${re_domain#*\\.}"
echo "$san" | grep -qE "DNS:($re_domain|$re_parent)(,|$| )" \
  || { echo "The certificate /certs/origin.crt does not cover $DOMAIN ($san)" >&2; exit 1; }

backup="$CADDYFILE.before-kingscastles"
cp -p "$CADDYFILE" "$backup"
new="$(mktemp)"
# everything except our old block, then our block at the end
awk '/^# BEGIN kings-and-castles/{skip=1} !skip{print} /^# END kings-and-castles/{skip=0}' "$CADDYFILE" > "$new"
{ echo "# BEGIN kings-and-castles (written by deploy/install.sh, do not edit here)"; echo "$block"; echo "# END kings-and-castles"; } >> "$new"
# the file is bind-mounted into the container: write it in place so the container sees the same file
cat "$new" > "$CADDYFILE" && rm -f "$new"
if ! docker exec "$PROXY" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1; then
  cat "$backup" > "$CADDYFILE"
  echo "Caddy did not accept the new config; the old Caddyfile is back:" >&2
  docker exec "$PROXY" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >&2 || true
  exit 1
fi
docker exec "$PROXY" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
echo "ok: Caddy serves https://$DOMAIN (backup of the old Caddyfile: $backup)"
