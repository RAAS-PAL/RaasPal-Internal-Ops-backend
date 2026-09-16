# Deploying the RaasPal API on AWS Lightsail

Every command here runs **on the Lightsail instance over SSH**, unless it says
otherwise. Render keeps serving users until step 7 — nothing before that is
visible to anyone.

## 1 · Prepare the box

```bash
sudo apt update && sudo apt upgrade -y
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu          # log out and back in for this to apply
sudo apt install -y nginx certbot python3-certbot-nginx git

# 2 GB swap — cheap insurance while Maven builds the image
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

sudo apt install -y unattended-upgrades
```

## 2 · Clone and configure

```bash
cd ~
git clone <repo-url> RaasPal-Internal-Ops-backend
cd RaasPal-Internal-Ops-backend/deploy

cp api.env.example api.env
nano api.env          # paste the values copied out of Render
chmod 600 api.env     # only ubuntu may read it — www-data must not
```

Five values differ from Render and are easy to miss:

| Variable | Value |
|---|---|
| `DB_POOL_MAX` | `6` while both hosts are live, `10` after |
| `TELEMETRY_SYNC_ENABLED` | `false` until Render is off, then `true` |
| `CASE_REPORT_SYNC_ENABLED` | `false` until Render is off, then `true` |
| `REPORT_EMAIL_SCHEDULER_ENABLED` | `false` — permanently, see below |
| `AI_PROVIDER` | `claude` — it defaults to `mock`, which fabricates proposals |

Telemetry sync is the one with effects outside the company during the overlap: two
live deployments would pull the same robots twice.

**The report email scheduler stays off for good.** Monthly reports are reviewed and
sent by hand, one customer at a time. Enabling the cron would mail unreviewed
reports to customers, which is worse than sending none.

`AI_PROVIDER` is the quiet one. Omit it and the app starts happily on
[[MockAiService]], producing proposals that look real and are invented — the
migration appears to succeed while the output is worthless.

`MONDAY_API_TOKEN` is the one that is **not on Render at all** yet, so copying values
out of Render will not bring it across. Without it the pending-case reports fail on
their first monday call. Take it from monday.com (avatar → Developers → My access tokens).

## 3 · Start it

```bash
docker compose up -d --build     # first build pulls the whole Maven tree, several minutes
docker compose logs -f           # Ctrl+C to stop watching
curl localhost:8080/v3/api-docs  # JSON means the app is up
```

## 4 · Put nginx in front

```bash
sudo cp nginx/raaspal-api.conf /etc/nginx/sites-available/raaspal-api
sudo ln -s /etc/nginx/sites-available/raaspal-api /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx

curl -H "Host: api.raaspal.com" http://localhost/v3/api-docs
```

That last command proves the whole chain works before DNS exists.

## 5 · DNS and TLS

Ask whoever manages `raaspal.com` for an **A record**: host `api`, value the
static IP, TTL 300. It is a new subdomain and touches neither the website nor
email — saying so usually gets it approved the same day.

```bash
dig api.raaspal.com +short              # wait until this returns your IP
sudo certbot --nginx -d api.raaspal.com
sudo certbot renew --dry-run            # prove renewal works
```

## 6 · Verify end to end

Still zero user impact. Sign in with a real account against
`https://api.raaspal.com` and exercise what touches the outside world: upload a
survey, generate a proposal, open a report, load the RIMS inventory endpoints.

```bash
# The endpoint lists should match between old and new
curl -s https://api.raaspal.com/v3/api-docs | head -c 400
```

## 7 · Cut the frontends over

The only user-visible moment.

- Console (Vercel): `NEXT_PUBLIC_API_URL=https://api.raaspal.com`, redeploy
- RIMS (Vercel): `RAASPAL_API_URL=https://api.raaspal.com`, redeploy

Rolling back means putting the two Vercel variables back to the `onrender.com`
URL. No DNS change, no wait — the frontends address the backend directly.

## 8 · After 48 quiet hours

```bash
nano deploy/api.env     # TELEMETRY_SYNC_ENABLED=true, CASE_REPORT_SYNC_ENABLED=true, DB_POOL_MAX=10
                        # REPORT_EMAIL_SCHEDULER_ENABLED stays false
bash deploy/deploy.sh
```

Take a Lightsail snapshot, then suspend the Render service.

---

## Redeploying afterwards

```bash
ssh ubuntu@<ip> 'bash ~/RaasPal-Internal-Ops-backend/deploy/deploy.sh'
```

Pulls, rebuilds, restarts, and waits for the app to answer before reporting
success.

## When something is wrong

```bash
docker compose logs --tail 100      # the application
docker compose ps                   # is the container up
sudo journalctl -u nginx -n 50      # the proxy
sudo nginx -t                       # config syntax
df -h && free -h                    # disk and memory
```
