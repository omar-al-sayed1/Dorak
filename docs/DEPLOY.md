# Deploying Dorak (M7)

One small VPS runs everything with Docker Compose: PostgreSQL, the app, Caddy (HTTPS) and a backup
container. GitHub Actions builds the app image on every push to `main` once the tests pass; the
server only pulls it. The files for the server are in [`deploy/`](../deploy).

```
Internet ──443──> Caddy ──> app:8080 ──> db (PostgreSQL 16)
                                            ^
                         backup (nightly) ──┘──> Backblaze B2 (encrypted)
                         app health watch ──> Telegram (alerts to you)
```

## What you need first

| What | Where | Notes |
| --- | --- | --- |
| A VPS | Hetzner, DigitalOcean, Contabo… | Ubuntu 24.04, **x86 (amd64)**, 2 vCPU, 2 GB RAM or more, 40 GB disk. The image is built for amd64 only, so not an ARM server (Hetzner CAX). |
| A domain | Any registrar | An `A` record (e.g. `app.yourdomain.com`) pointing at the VPS's IPv4 address. |
| Backblaze B2 | backblaze.com | A **private** bucket, and an application key limited to that bucket with Read and Write. |
| A Telegram bot | @BotFather in Telegram | `/newbot` gives the token. Send the bot any message, then open `https://api.telegram.org/bot<token>/getUpdates`: `"chat":{"id":…}` is your chat id. |
| Meta app values | Meta app dashboard | App secret and the Graph API version (SPEC section 6). |

## 1. Prepare the server (once)

Log in as root (`ssh root@<server-ip>`), then:

```bash
apt update && apt -y upgrade
apt -y install unattended-upgrades ufw git
dpkg-reconfigure -plow unattended-upgrades

# A user of your own, with your SSH key, so root can stop logging in.
adduser omar
usermod -aG sudo omar
rsync --archive --chown=omar:omar ~/.ssh /home/omar

# Firewall: SSH and the web only. Docker publishes only Caddy's ports (80, 443); the database has none.
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw enable

# Docker (Docker's own install script) and Compose.
curl -fsSL https://get.docker.com | sh
usermod -aG docker omar
```

Check you can log in as `omar` with your key in a **new** terminal, then turn off root and password
logins: in `/etc/ssh/sshd_config` set `PermitRootLogin no` and `PasswordAuthentication no`, then
`systemctl restart ssh`.

## 2. Get the deploy files and fill in `.env`

As `omar`:

```bash
git clone https://github.com/omar-al-sayed1/Dorak.git ~/dorak
cd ~/dorak/deploy
cp .env.example .env
chmod 600 .env
nano .env
```

Each line of `.env.example` says what goes there. To generate the random values:

```bash
openssl rand -base64 32 | tr -d '/+='   # POSTGRES_PASSWORD
openssl rand -hex 24                    # WA_VERIFY_TOKEN
openssl rand -base64 32                 # TOKEN_ENCRYPTION_KEY
openssl rand -base64 32                 # BACKUP_PASSPHRASE
```

**Put `TOKEN_ENCRYPTION_KEY` and `BACKUP_PASSPHRASE` in your password manager now.** Without the
passphrase, the backups can't be read; without the key, every clinic has to reconnect WhatsApp.

## 3. First start

The first push to `main` after this milestone builds the image (GitHub → Actions → CI → `image`
job). Then, on the server:

```bash
cd ~/dorak/deploy
./deploy.sh
```

It pulls the image, starts everything and waits until `https://<DOMAIN>/actuator/health` answers.
Caddy gets the HTTPS certificate on its own the first time (the domain must already point at the
server, and ports 80 and 443 must be open).

If `docker compose pull` says *denied*: on GitHub, open the repository → Packages → `dorak` →
Package settings → Change visibility → Public. Or log the server in with a token that can read
packages: `docker login ghcr.io -u omar-al-sayed1`.

Create your operator login (it prints a password once):

```bash
docker compose run --rm app --create-operator=omar
```

Then:
1. Sign in at `https://<DOMAIN>/operator` and set Meta's message rates (Operator → Message rates).
2. In the Meta app dashboard, set the webhook to `https://<DOMAIN>/webhooks/whatsapp` with your
   `WA_VERIFY_TOKEN`, and subscribe to `messages`.
3. Add the first clinic and connect its number (Operator → Clinics).
4. Set up an uptime check (next section).

## 4. Alerts

The app checks itself every minute and messages you on Telegram when:

| Alert | Means |
| --- | --- |
| Reminders are late | Reminders still waiting 10 minutes after their time: the scheduler stalled. |
| Incoming WhatsApp messages are not being processed | Webhook deliveries stuck for 10 minutes: replies and button presses are not handled. |
| WhatsApp sends are failing | 3 or more failed sends, and at least half of all sends, in 15 minutes. Usually an expired token, a blocked number or a template problem. |
| No recent database backup | No successful backup for 26 hours. |
| The database cannot be reached | Self-explanatory. |

A problem that lasts is repeated every 6 hours, and you get a ✅ message when it clears. A failed
nightly backup is also reported at once by the backup container.

**What the app can't tell you is that the whole server is down.** For that, add a free external
check, e.g. UptimeRobot or Better Stack: an HTTP monitor on `https://<DOMAIN>/actuator/health`
every 5 minutes, alerting you by email or their app.

Test the Telegram settings once, right after the first start:

```bash
docker compose exec backup sh -c 'curl -fsS "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" --data-urlencode "chat_id=$TELEGRAM_CHAT_ID" --data-urlencode "text=Dorak test alert" >/dev/null && echo sent'
```

## 5. Backups

Every night at 03:30 (Beirut), the `backup` container:
1. dumps the database with `pg_dump` and checks the dump can be read back;
2. copies it to B2, **encrypted with `BACKUP_PASSPHRASE`** (rclone crypt; file names stay readable);
3. deletes copies older than 30 days in B2, and older than 7 days on the server;
4. records the success, which is what the app's "no recent backup" alert watches.

Take one by hand (do this right after the first start, to check the B2 settings):

```bash
docker compose exec backup backup-once.sh
```

### Restore

```bash
./restore.sh list                          # the dumps in B2
./restore.sh check dorak-2026-11-20_0330.dump
./restore.sh live  dorak-2026-11-20_0330.dump
```

- **`check`** restores into a scratch database, prints clinic, patient and appointment counts,
  and drops it. The live database is not touched. **Do it once after the first backup, then once a
  month** (SPEC section 8 asks for a tested restore).
- **`live`** replaces the live database with that dump. It stops the app, asks you to type the dump
  name, restores, and starts the app again. Everything since that backup is lost.

On a new server after a disaster: steps 1–2, `docker compose up -d db backup`, then
`./restore.sh live <dump>` and `./deploy.sh`.

## 6. Updating

Push to `main`. When the CI run is green (tests, then `image`), on the server:

```bash
cd ~/dorak && git pull && cd deploy && ./deploy.sh
```

(`git pull` only matters when files in `deploy/` changed; the app itself comes from the image.)
Database migrations run when the app starts.

**Roll back** to an earlier build: find its commit on GitHub, then
`DORAK_TAG=sha-<first 7 characters> ./deploy.sh`. That works as long as the newer build didn't
change the database in a way the older one can't read; when in doubt, `./restore.sh live` the
backup from before the update.

## 7. Day to day

```bash
docker compose ps                        # what runs, and the app's health
docker compose logs -f app               # the app's log (also: caddy, backup, db)
docker compose run --rm app --seed-demo  # refresh the "Dr. Demo" sales demo clinic
docker compose restart app
df -h && docker system df                # disk space
```

Logs are capped at 100 MB per service. The app gets 1 GB of memory, which leaves room for
PostgreSQL on a 2 GB server.
