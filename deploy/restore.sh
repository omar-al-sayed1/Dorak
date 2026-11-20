#!/usr/bin/env bash
# Restoring a backup, on the server, from this folder (docs/DEPLOY.md, "Backups"):
#   ./restore.sh list                  the dumps off the server (B2), newest last
#   ./restore.sh check <dump>          restore into a scratch database and count rows: the live
#                                      database is not touched. Do this once a month.
#   ./restore.sh live <dump>           replace the live database with the dump (the app is stopped
#                                      meanwhile). Asks for confirmation.
# <dump> is a name from the list, e.g. dorak-2026-11-20_0330.dump. It is fetched from B2 unless the
# server still has it.
set -euo pipefail
cd "$(dirname "$0")"

# </dev/null: docker compose exec would otherwise read this script's input, including the confirmation.
backup() { docker compose exec -T backup "$@" < /dev/null; }

fetch() {
    local name="$1"
    if ! backup test -f "/backups/$name"; then
        echo "Fetching $name from B2"
        backup with-remote rclone copyto "offsite:$name" "/backups/$name"
    fi
    backup pg_restore --list "/backups/$name" > /dev/null
}

counts() {
    backup psql --no-psqlrc -d "$1" -c "select
        (select count(*) from clinic) as clinics,
        (select count(*) from patient) as patients,
        (select count(*) from appointment) as appointments,
        (select max(starts_at) from appointment) as latest_appointment,
        (select version from flyway_schema_history order by installed_rank desc limit 1) as schema_version"
}

case "${1:-}" in
    list)
        backup with-remote rclone lsl "offsite:" | sort -k2,3
        ;;
    check)
        name="${2:?Which dump? See ./restore.sh list}"
        fetch "$name"
        backup dropdb --if-exists dorak_restore_check
        backup createdb dorak_restore_check
        backup pg_restore --no-owner --exit-on-error -d dorak_restore_check "/backups/$name"
        echo "Restored $name into a scratch database:"
        counts dorak_restore_check
        backup dropdb dorak_restore_check
        echo "Restore check passed. The scratch database is gone; the live one was not touched."
        ;;
    live)
        name="${2:?Which dump? See ./restore.sh list}"
        fetch "$name"
        echo "This REPLACES the live database with $name. Everything since that backup is lost."
        read -r -p "Type the dump name to go on: " typed
        [[ "$typed" == "$name" ]] || { echo "Not confirmed; nothing changed."; exit 1; }
        docker compose stop app
        backup pg_restore --no-owner --clean --if-exists --exit-on-error -d dorak "/backups/$name"
        counts dorak
        docker compose start app
        echo "Restored. The app is starting again."
        ;;
    *)
        sed -n '2,9p' "$0"
        exit 1
        ;;
esac
