#!/bin/bash
# Builds the game (server + web client) on this machine and puts it on the live server, then checks it answers.
#   ./deploy.bash              build, test, copy, restart
#   ./deploy.bash --skip-tests faster, when only the client changed
# The server's setup is described in mm_home/goals/riffs_goals/SERVER_SETUP.md.
set -euo pipefail

HOST=${RIFFS_HOST:-mm@riffs.mckcode.com}
URL=${RIFFS_URL:-https://riffs.mckcode.com}
HERE=$(cd "$(dirname "$0")" && pwd)
CLIENT=$HERE/../riffs_client_vue
REMOTE_DIR=riffs_server    # in mm's home on the server

[ -n "${SSH_AUTH_SOCK:-}" ] || export SSH_AUTH_SOCK=$(ls ~/.ssh/agent/s.* 2>/dev/null | head -1)

echo "== web client"
(cd "$CLIENT" && source ~/.nvm/nvm.sh >/dev/null && nvm use >/dev/null && npm run build >/dev/null)

echo "== server"
if [ "${1:-}" = "--skip-tests" ]; then
  (cd "$HERE" && mvn -q -o package -DskipTests)
else
  (cd "$HERE" && mvn -q -o package)
fi
JAR=$(ls "$HERE"/target/riffs_server-*.jar | head -1)

echo "== copy to $HOST"
rsync -az "$JAR" "$HOST:$REMOTE_DIR/riffs_server.jar.new"
rsync -az --delete "$CLIENT/dist/" "$HOST:$REMOTE_DIR/local/static/"
ssh "$HOST" "mv $REMOTE_DIR/riffs_server.jar.new $REMOTE_DIR/riffs_server.jar && sudo -n systemctl restart riffs-server"

echo "== waiting for $URL"
for i in $(seq 60); do
  if out=$(curl -sf "$URL/api/health"); then echo "up: $out"; exit 0; fi
  sleep 1
done
echo "not answering after 60 s; on the server: journalctl -u riffs-server -n 50" >&2
exit 1
