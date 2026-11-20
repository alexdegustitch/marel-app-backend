#!/usr/bin/env bash
# Deploys one backend image tag. Called by GitHub Actions over SSH:
#   /opt/marel/deploy.sh <git-sha>
# or by hand for a ROLLBACK:
#   /opt/marel/deploy.sh <older-git-sha>
#
# What it does: dump the database first, pin APP_IMAGE_TAG in .env, pull the
# image, restart only the app container, then wait until /actuator/health says UP.
# A failed health check exits non-zero so the Actions run goes red - the OLD
# container is gone by then, so a red deploy means: roll back (run this script
# with the previous sha, printed below).

set -euo pipefail
cd /opt/marel

TAG="${1:?usage: deploy.sh <image-tag>}"

PREVIOUS="$(grep -E '^APP_IMAGE_TAG=' .env | cut -d= -f2-)"
echo "==> Deploying ${TAG} (previous: ${PREVIOUS})"

# Safety net before any Flyway migration runs: a dump we can restore.
# Skipped only when the database container is not up yet (very first install).
if docker compose ps --status running db --quiet | grep -q .; then
    ./backup.sh pre-deploy
else
    echo "==> db container not running - skipping pre-deploy dump (first install?)"
fi

sed -i "s|^APP_IMAGE_TAG=.*|APP_IMAGE_TAG=${TAG}|" .env

docker compose pull --quiet app
docker compose up -d app

echo "==> Waiting for /actuator/health ..."
for _ in $(seq 1 60); do
    if curl -fs http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then
        echo "==> Health OK - ${TAG} is live."
        docker image prune -f >/dev/null
        exit 0
    fi
    sleep 3
done

echo "!!! Health check FAILED for ${TAG}. Last log lines:"
docker compose logs --tail 80 app || true
echo "!!! To roll back:  /opt/marel/deploy.sh ${PREVIOUS}"
exit 1
