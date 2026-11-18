#!/usr/bin/env bash
# Installs or updates the Kings & Castles server on a Debian/Ubuntu VPS (mikr.us).
# Run as root from a clone of the repository:   sudo deploy/install.sh
# Running it again later updates the game: it pulls main, builds, and restarts. Saved rooms stay.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_DIR"

echo "== packages (Java 21, Maven, Caddy)"
apt-get update -qq
apt-get install -y -qq maven curl git debian-keyring debian-archive-keyring apt-transport-https gnupg >/dev/null
if ! apt-get install -y -qq openjdk-21-jdk-headless >/dev/null 2>&1; then
  # Debian 12 has no Java 21 of its own: take it from Adoptium (Eclipse Temurin)
  curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
  echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo "$VERSION_CODENAME") main" \
    > /etc/apt/sources.list.d/adoptium.list
  apt-get update -qq
  apt-get install -y -qq temurin-21-jdk >/dev/null
fi
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -qq
  apt-get install -y -qq caddy >/dev/null
fi

JAVA21="$(ls -d /usr/lib/jvm/*21* | head -1)"
[ -x "$JAVA21/bin/java" ] || { echo "Java 21 not found in /usr/lib/jvm" >&2; exit 1; }

echo "== build"
if [ -d .git ]; then
  git pull --ff-only origin main
fi
JAVA_HOME="$JAVA21" mvn -q -B -DskipTests package

echo "== install"
id kingscastles >/dev/null 2>&1 || useradd --system --home /opt/kingscastles --shell /usr/sbin/nologin kingscastles
mkdir -p /opt/kingscastles /var/lib/kingscastles
cp server/target/server-*.jar /opt/kingscastles/server.jar
ln -sf "$JAVA21/bin/java" /opt/kingscastles/java
chown -R kingscastles:kingscastles /var/lib/kingscastles
cp deploy/kingscastles.service /etc/systemd/system/kingscastles.service
systemctl daemon-reload
systemctl enable --now kingscastles
systemctl restart kingscastles

if [ ! -f /etc/caddy/Caddyfile ] || ! grep -q kc.pointsapp.pl /etc/caddy/Caddyfile; then
  cp deploy/Caddyfile /etc/caddy/Caddyfile
  systemctl reload caddy || systemctl restart caddy
fi

echo "== check"
for i in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8080/api/rules >/dev/null; then
    echo "The server is running. Try: https://kc.pointsapp.pl/api/rules"
    exit 0
  fi
  sleep 2
done
echo "The server did not start. Look at: journalctl -u kingscastles -n 50" >&2
exit 1
