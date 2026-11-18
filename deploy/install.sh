#!/usr/bin/env bash
# Installs or updates the Kings & Castles server on a Debian/Ubuntu VPS (mikr.us). Run as root.
#
# Normally GitHub Actions runs it (.github/workflows/deploy.yml): it builds the game, copies this folder with
# release/server.jar and release/client/ to the server, and starts this script there.
# Running it again updates the game. Saved rooms stay.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DOMAIN="${KC_DOMAIN:-kc.pointsapp.pl}"
[ -f "$HERE/release/server.jar" ] && [ -f "$HERE/release/client/index.html" ] \
  || { echo "Missing release/server.jar or release/client/ next to this script (GitHub Actions puts them there)" >&2; exit 1; }

echo "== packages (Java 21, Caddy)"
export DEBIAN_FRONTEND=noninteractive
if ! ls -d /usr/lib/jvm/*21* >/dev/null 2>&1 || ! command -v caddy >/dev/null; then
  apt-get update -qq
  apt-get install -y -qq curl gnupg debian-keyring debian-archive-keyring apt-transport-https >/dev/null
fi
if ! ls -d /usr/lib/jvm/*21* >/dev/null 2>&1; then
  if ! apt-get install -y -qq openjdk-21-jre-headless >/dev/null 2>&1; then
    # Debian 12 has no Java 21 of its own: take it from Adoptium (Eclipse Temurin)
    curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor --yes -o /usr/share/keyrings/adoptium.gpg
    echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo "$VERSION_CODENAME") main" \
      > /etc/apt/sources.list.d/adoptium.list
    apt-get update -qq
    apt-get install -y -qq temurin-21-jre >/dev/null
  fi
fi
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -qq
  apt-get install -y -qq caddy >/dev/null
fi

JAVA21="$(ls -d /usr/lib/jvm/*21* | head -1)"
[ -x "$JAVA21/bin/java" ] || { echo "Java 21 not found in /usr/lib/jvm" >&2; exit 1; }

echo "== install"
id kingscastles >/dev/null 2>&1 || useradd --system --home /opt/kingscastles --shell /usr/sbin/nologin kingscastles
mkdir -p /opt/kingscastles /var/lib/kingscastles
# the new client goes in next to the old one and is switched in one step
rm -rf /opt/kingscastles/client.new
cp -r "$HERE/release/client" /opt/kingscastles/client.new
cp "$HERE/release/server.jar" /opt/kingscastles/server.jar.new
mv -f /opt/kingscastles/server.jar.new /opt/kingscastles/server.jar
rm -rf /opt/kingscastles/client.old
[ -d /opt/kingscastles/client ] && mv /opt/kingscastles/client /opt/kingscastles/client.old
mv /opt/kingscastles/client.new /opt/kingscastles/client
rm -rf /opt/kingscastles/client.old
ln -sfn "$JAVA21/bin/java" /opt/kingscastles/java
# the game files belong to root (the service only reads them); only the saved rooms are writable
chown -R root:root /opt/kingscastles
chmod -R a+rX /opt/kingscastles
chown -R kingscastles:kingscastles /var/lib/kingscastles
chmod 750 /var/lib/kingscastles
sed "s/kc.pointsapp.pl/$DOMAIN/g" "$HERE/kingscastles.service" > /etc/systemd/system/kingscastles.service
systemctl daemon-reload
systemctl enable kingscastles >/dev/null
systemctl restart kingscastles

sed "s/kc.pointsapp.pl/$DOMAIN/g" "$HERE/Caddyfile" > /etc/caddy/kingscastles.caddy
if ! grep -q 'import /etc/caddy/kingscastles.caddy' /etc/caddy/Caddyfile 2>/dev/null; then
  # a fresh Caddy comes with a demo site on port 80: replace it; anything else stays and our site is added
  if grep -q '^:80' /etc/caddy/Caddyfile 2>/dev/null; then
    echo 'import /etc/caddy/kingscastles.caddy' > /etc/caddy/Caddyfile
  else
    echo 'import /etc/caddy/kingscastles.caddy' >> /etc/caddy/Caddyfile
  fi
fi
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null
systemctl reload caddy || systemctl restart caddy

echo "== check"
for i in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8080/api/rules >/dev/null && curl -fsS http://127.0.0.1:8080/ | grep -q '<html'; then
    echo "The server is running. Try: https://$DOMAIN/"
    exit 0
  fi
  sleep 2
done
echo "The server did not start. Look at: journalctl -u kingscastles -n 50" >&2
journalctl -u kingscastles -n 50 --no-pager >&2 || true
exit 1
