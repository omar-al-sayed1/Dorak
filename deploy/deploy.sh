#!/usr/bin/env bash
# Puts the latest image live, on the server, from this folder:
#   ./deploy.sh                    the newest build of main
#   DORAK_TAG=sha-1a2b3c4 ./deploy.sh   a given build (to roll back)
# Pulls the image GitHub Actions built, restarts what changed, and waits until the app answers on
# https://$DOMAIN. Flyway applies new migrations when the app starts.
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f .env ]]; then
    echo "No .env here: copy .env.example to .env and fill it in (docs/DEPLOY.md)." >&2
    exit 1
fi
DOMAIN="$(grep -E '^DOMAIN=' .env | tail -1 | cut -d= -f2-)"

echo "Pulling ${DORAK_IMAGE:-ghcr.io/omar-al-sayed1/dorak}:${DORAK_TAG:-latest}"
docker compose pull app caddy db
docker compose up -d --build --remove-orphans

echo -n "Waiting for https://$DOMAIN/actuator/health "
for _ in $(seq 1 60); do
    if curl -fsS -m 5 "https://$DOMAIN/actuator/health" 2>/dev/null | grep -q '"UP"'; then
        echo " up."
        docker image prune -f > /dev/null
        docker compose ps
        exit 0
    fi
    echo -n "."
    sleep 5
done
echo
echo "The app did not come up within 5 minutes. Its log:" >&2
docker compose logs --tail 80 app >&2
exit 1
