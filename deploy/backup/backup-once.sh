#!/bin/sh
# One backup: dump the database, check the dump can be read back, copy it off the server
# (encrypted), drop old copies, and record the success in ops_heartbeat so the app knows (it alerts
# the operator when the last success is more than a day old). Any failed step stops it with an error.
#   docker compose exec backup backup-once.sh      (by hand, from deploy/)
set -eu

stamp="$(date +%Y-%m-%d_%H%M)"
name="dorak-$stamp.dump"
file="/backups/$name"

echo "Backup $name: dumping"
pg_dump --format=custom --no-owner --file="$file.part"
mv "$file.part" "$file"

# A dump that pg_restore cannot list is no backup.
pg_restore --list "$file" > /dev/null
echo "Backup $name: $(du -h "$file" | cut -f1), readable"

with-remote rclone copyto "$file" "offsite:$name"
# The copy must be there, the same size, before it counts (sizes through the crypt layer are the
# decrypted ones).
local_size="$(stat -c %s "$file")"
remote_size="$(with-remote rclone lsf --format s "offsite:$name")"
if [ "$remote_size" != "$local_size" ]; then
    echo "Backup $name: the off-site copy is $remote_size bytes, expected $local_size" >&2
    exit 1
fi
echo "Backup $name: copied off the server"

with-remote rclone delete --min-age "${BACKUP_KEEP_DAYS:-30}d" "offsite:"
# On the server itself, a week is enough: the off-site copies are the real backups.
find /backups -name 'dorak-*.dump' -mtime +7 -delete

psql --quiet --no-psqlrc --command \
    "insert into ops_heartbeat (name, at) values ('backup', now()) on conflict (name) do update set at = excluded.at"
echo "Backup $name: done"
