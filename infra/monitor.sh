#!/bin/sh
set -eu
# Schedule every minute in the deployment's monitoring runner; nonzero exit triggers its configured alert.
base=${ONFIT_HEALTH_BASE_URL:-http://127.0.0.1:8080}
attempt=0
while ! curl --fail --silent --max-time 5 "$base/actuator/health/readiness" > /dev/null; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 3 ]; then printf 'alert readiness_failed\n' >&2; exit 1; fi
    sleep 5
done
directory=${ONFIT_BACKUP_DIR:?Set the backup directory}
if [ ! -f "$directory/last-success" ] || ! find "$directory/last-success" -mmin -1560 | grep -q .; then
    printf 'alert backup_older_than_26_hours\n' >&2; exit 1
fi
used=$(df -P "$directory" | awk 'NR==2 {gsub(/%/, "", $5); print $5}')
if [ "$used" -ge 80 ]; then printf 'alert backup_disk_usage=%s%%\n' "$used" >&2; exit 1; fi
printf 'monitor_ok\n'
