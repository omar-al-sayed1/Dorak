#!/bin/sh
# Runs backup-once.sh every night at BACKUP_TIME (TZ, Asia/Beirut), and tells the operator on
# Telegram at once when one fails. If this container stops altogether, the app notices instead: no
# backup recorded for a day.
set -u

alert() {
    echo "$1"
    if [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${TELEGRAM_CHAT_ID:-}" ]; then
        curl -fsS -m 20 "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
            --data-urlencode "chat_id=$TELEGRAM_CHAT_ID" --data-urlencode "text=$1" > /dev/null \
            || echo "Could not send the alert to Telegram"
    fi
}

seconds_until() {
    now="$(date +%s)"
    next="$(date -d "$(date +%Y-%m-%d) $1" +%s)"
    if [ "$next" -le "$now" ]; then
        next=$((next + 86400))
    fi
    echo $((next - now))
}

echo "Backups every day at ${BACKUP_TIME:-03:30} ($TZ)"
while true; do
    sleep "$(seconds_until "${BACKUP_TIME:-03:30}")"
    if ! backup-once.sh; then
        alert "⚠️ Dorak (${SERVER_NAME:-server})
The nightly database backup failed. See: docker compose logs backup"
    fi
    # Not twice in the same minute.
    sleep 60
done
