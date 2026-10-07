#!/usr/bin/env bash
# Nightly backup of the box's PostgreSQL (raaspal-db): one compressed dump,
# kept 14 days on the box and copied to S3 so the data outlives the box.
#
# Runs from cron, e.g. every night at 02:00 Bangkok:
#   0 19 * * * bash ~/RaasPal-Internal-Ops-backend/deploy/db-backup.sh >> ~/db-backups/backup.log 2>&1
#
# Needs ~/.config/raaspal-backup/aws.env (mode 600, never in git) with
# AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_DEFAULT_REGION and BACKUP_BUCKET.
# That key may list, upload and download in the bucket but not delete: a leaked
# key cannot wipe the backups. The bucket's own 30-day rule removes old copies.
set -euo pipefail

KEEP_DAYS=${KEEP_DAYS:-14}
DIR=${BACKUP_DIR:-$HOME/db-backups/nightly}
ENV_FILE=${AWS_ENV_FILE:-$HOME/.config/raaspal-backup/aws.env}
CONTAINER=${DB_CONTAINER:-raaspal-db}

[ -r "$ENV_FILE" ] || { echo "backup FAILED: $ENV_FILE is missing" >&2; exit 1; }
BUCKET=$(sed -n 's/^BACKUP_BUCKET=//p' "$ENV_FILE")
[ -n "$BUCKET" ] || { echo "backup FAILED: BACKUP_BUCKET is not set in $ENV_FILE" >&2; exit 1; }

mkdir -p "$DIR"
chmod 700 "$DIR"
name="raaspal-$(date -u +%Y-%m-%dT%H%MZ).dump"
file="$DIR/$name"
tmp="$file.partial"
trap 'rm -f "$tmp"' EXIT

# Custom format: compressed, and pg_restore can restore it table by table.
docker exec "$CONTAINER" pg_dump -U raaspal -d raaspal --format=custom > "$tmp"
# A dump that pg_restore cannot read back is not a backup.
docker exec -i "$CONTAINER" pg_restore --list < "$tmp" > /dev/null
mv "$tmp" "$file"
chmod 600 "$file"

docker run --rm --env-file "$ENV_FILE" -v "$file:/backup/$name:ro" amazon/aws-cli \
  s3 cp "/backup/$name" "s3://$BUCKET/nightly/$name" --only-show-errors

find "$DIR" -name 'raaspal-*.dump' -mtime +"$KEEP_DAYS" -delete
echo "$(date -u +%FT%TZ) backup ok: $name $(du -h "$file" | cut -f1) → s3://$BUCKET/nightly/"
