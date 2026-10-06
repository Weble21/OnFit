#!/bin/sh
set -eu
umask 077
export PGPASSWORD="$(cat /run/secrets/DB_PASSWORD)"
mkdir -p /backups/daily /backups/weekly
stamp=$(date -u +%Y%m%dT%H%M%SZ)
target="/backups/daily/onfit-$stamp.dump"
trap 'rm -f "$target.tmp"' EXIT HUP INT TERM
pg_dump --format=custom --file="$target.tmp"
pg_restore --list "$target.tmp" > /dev/null
mv "$target.tmp" "$target"
if [ "$(date -u +%u)" = 7 ]; then
    cp "$target" "/backups/weekly/onfit-$(date -u +%G-W%V).dump"
fi
find /backups/daily -type f -name 'onfit-*.dump' -mtime +6 -delete
find /backups/weekly -type f -name 'onfit-*.dump' -mtime +27 -delete
date -u +%Y-%m-%dT%H:%M:%SZ > /backups/last-success.tmp
mv /backups/last-success.tmp /backups/last-success
printf 'backup_success file=%s\n' "$target"
