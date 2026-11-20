# The backup container: pg_dump (same major version as the database) and rclone, to copy each dump
# off the server, encrypted, to Backblaze B2. Built on the server by docker compose (it is small).
FROM postgres:16-alpine
RUN apk add --no-cache rclone curl tzdata
COPY with-remote backup-once.sh backup-loop.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/with-remote /usr/local/bin/backup-once.sh /usr/local/bin/backup-loop.sh
VOLUME /backups
ENTRYPOINT []
CMD ["backup-loop.sh"]
