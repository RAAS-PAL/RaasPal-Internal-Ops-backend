#!/usr/bin/env bash
# Build and start the preview API from a feature branch, on the Lightsail box.
#
#   ssh -i ~/.ssh/lightsail.pem ubuntu@api.raaspal.com 'bash ~/RaasPal-Internal-Ops-backend/deploy/preview/run-branch-preview.sh feat/brand-tickets'
#
# Touches only the preview stack (raaspal-api:preview on 127.0.0.1:8081 and its
# sidecar Postgres). Production - the raaspal-api container, nginx's production
# site and Supabase - is not read or written. Leaves the checkout on the branch;
# run `git checkout main` when finished so deploy.sh never builds prod from it.
set -euo pipefail

BRANCH="${1:?usage: run-branch-preview.sh <branch>}"
REPO="$HOME/RaasPal-Internal-Ops-backend"

cd "$REPO"
git fetch origin
git checkout "$BRANCH"
git pull --ff-only origin "$BRANCH"
echo "== checkout: $(git log --oneline -1)"

cd deploy/preview
if grep -q '^MONDAY_API_TOKEN=TODO' preview.env; then
  sed -i "s|^MONDAY_API_TOKEN=.*|$(grep '^MONDAY_API_TOKEN=' ../api.env)|" preview.env
  echo "== MONDAY_API_TOKEN copied from api.env"
fi

docker compose up -d --build
echo "== api port binding: $(grep -s '^PREVIEW_BIND' .env || echo 'PREVIEW_BIND unset -> 127.0.0.1 (tunnel only)')"
echo "== waiting for the api to boot"
for i in $(seq 1 60); do
  if curl -fs localhost:8081/actuator/health >/dev/null 2>&1; then break; fi
  sleep 5
done
curl -s localhost:8081/actuator/health; echo
docker compose ps

# nginx preview site, if not already enabled (DNS A record must exist before certbot)
if [ ! -e /etc/nginx/sites-enabled/raaspal-api-preview ]; then
  sudo cp nginx-preview.conf /etc/nginx/sites-available/raaspal-api-preview
  sudo ln -s /etc/nginx/sites-available/raaspal-api-preview /etc/nginx/sites-enabled/
  sudo nginx -t && sudo systemctl reload nginx
  echo "== nginx preview site enabled; run: sudo certbot --nginx -d pm-preview-api.raaspal.com"
fi
