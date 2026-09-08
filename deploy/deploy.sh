#!/usr/bin/env bash
#
# Redeploy the API from the current branch. Run on the server:
#
#     bash ~/robot-recommendation-api/deploy/deploy.sh
#
# Or, without opening a session, from your own machine:
#
#     ssh ubuntu@<ip> 'bash ~/robot-recommendation-api/deploy/deploy.sh'
#
# set -e stops at the first failure, so a broken build never gets as far as
# replacing a container that is currently serving traffic.
set -euo pipefail

cd "$(dirname "$0")"

echo "==> Pulling"
git -C .. pull --ff-only

echo "==> Building and restarting"
docker compose up -d --build

echo "==> Waiting for the JVM to come up"
# Spring takes 20-30s regardless of how the deploy happened. Polling beats a
# fixed sleep: it returns as soon as the app answers, and fails loudly if it
# never does.
for i in $(seq 1 30); do
    if curl -sf -o /dev/null http://127.0.0.1:8080/v3/api-docs; then
        echo "==> Up after ${i}0s"
        docker compose logs --tail 20
        exit 0
    fi
    sleep 10
done

echo "!!! Did not answer within 300s. Recent logs:"
docker compose logs --tail 60
exit 1
