#!/bin/sh
set -eu
umask 077
file=${1:?Pass a dump under /backups/daily or /backups/weekly}
case "$file" in
    /backups/daily/onfit-*.dump|/backups/weekly/onfit-*.dump) ;;
    *) printf 'Only OnFit backup paths are accepted\n' >&2; exit 1 ;;
esac
case "$file" in *..*) printf 'Invalid backup path\n' >&2; exit 1 ;; esac
export PGPASSWORD="$(cat /run/secrets/DB_PASSWORD)"
shadow="onfit_restore_check_$(date -u +%Y%m%d%H%M%S)_$$"
createdb "$shadow"
# This script drops only the unique database it just created, never PGDATABASE or the source database.
trap 'dropdb --if-exists "$shadow"' EXIT HUP INT TERM
pg_restore --dbname="$shadow" --no-owner --no-privileges --exit-on-error "$file"
psql --dbname="$shadow" --set=ON_ERROR_STOP=1 --command='
    SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;
    SELECT count(*) AS postings FROM job_postings;
    SELECT count(*) AS recommendations FROM recommendations;
'
printf 'restore_archive_check_success (application migration/API rehearsal is still required)\n'
