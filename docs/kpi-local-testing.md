# Testing the RE KPI monday.com sync locally

Everything below runs against a **throwaway docker Postgres**, never Supabase.
That matters: local dev and production share one Supabase database, so starting
the backend against it would apply **V38** to production.

## One-time setup

Already done on this machine, listed so it can be rebuilt:

```bash
docker run -d --name raaspal-kpi-pg \
  -e POSTGRES_PASSWORD=raaspal -e POSTGRES_DB=robot_recommendation_db \
  -p 5433:5432 postgres:16
```

`src/main/resources/application-local.properties` (gitignored) points at it and
sets `server.port=8081`, because **Jenkins owns port 8080** on this Mac.

## Each session

```bash
docker start raaspal-kpi-pg
cd ~/Desktop/RassPal/RaasPal-Internal-Ops-backend
git checkout feat/re-kpi-dashboard

export MONDAY_API_TOKEN='<paste your token>'      # never commit this
sh mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

`sh mvnw`, not `./mvnw` — the wrapper is not executable here and `mvn` is not on PATH.

Log in for a bearer token (the seeded admin, created on first start):

```bash
TOKEN=$(curl -s -X POST localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@raaspal.com","password":"Admin@1234"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')
H="Authorization: Bearer $TOKEN"
```

## Step 1 — confirm the token is seen

```bash
curl -s -H "$H" localhost:8081/api/v1/kpi/monday/config | python3 -m json.tool
```

`tokenConfigured` must be `true`. If it is `false`, the export did not reach the
JVM — export it in the same shell that runs Maven.

## Step 2 — read the real column ids (do this before syncing)

```bash
curl -s -H "$H" localhost:8081/api/v1/kpi/monday/boards/3451717331 | python3 -m json.tool  # Cleaning
curl -s -H "$H" localhost:8081/api/v1/kpi/monday/boards/1647612496 | python3 -m json.tool  # Delivery
```

This lists every column's `id`, `title` and `type`, plus the group ids. Compare
against the `app.kpi.monday.boards[*].columns.*` block in
`application.properties`. Only these were verified on 2026-08-26:

| Board | Verified | Assumed — check these |
|---|---|---|
| Cleaning `3451717331` | `date8` open date, `status`, `status_1` issue level, `text` main issue | `text6` branch, `asset_owner3__1` project, `text0` serial |
| Delivery `1647612496` | `status`, `status_1` sup status | `date5` open date, `asset_owner` serial, `text6` branch, `tags42` branch code, `tags2` project |

**Neither board has a close-date column mapped.** Without one, every ticket
reports `slaUnknown` and the SLA rate is null. Find the real column (or the
status values meaning "finished") and set it:

```bash
export APP_KPI_MONDAY_BOARDS_0_COLUMNS_CLOSEDATE=date_xxxx
export APP_KPI_MONDAY_BOARDS_0_CLOSED_STATUSES='Done,ปิดงาน'
```

Restart after changing these. Nothing needs a redeploy or a code change — the
mapping is configuration, and every column is archived in
`case_ticket.raw_columns` regardless, so a mapping corrected later applies on
the next sync without re-reading history.

## Step 3 — run a sync

```bash
curl -s -X POST -H "$H" localhost:8081/api/v1/kpi/monday/sync        # 202, runs in background
curl -s -H "$H" localhost:8081/api/v1/kpi/monday/sync/status | python3 -m json.tool
curl -s -H "$H" localhost:8081/api/v1/kpi/monday/sync/runs | python3 -m json.tool
```

A run row per board records items read/inserted/updated and, on failure, the
monday error. One board failing does not stop the other.

## Step 4 — read the KPIs

```bash
curl -s -H "$H" 'localhost:8081/api/v1/kpi/cm-cases?from=2026-01&to=2026-06' | python3 -m json.tool
```

Sanity checks against the deck (Jan–Jun 2026): 1,458 total cases,
643 cleaning / 815 delivery. A large gap means the wrong groups or the wrong
open-date column. The deck's FTFR uses 1,270 "KPI cases" out of 1,458, so it
excludes something this module does not — expect first-time-fix to differ until
the RE team confirms the rule.

Inspect rows directly:

```bash
docker exec raaspal-kpi-pg psql -U postgres -d robot_recommendation_db -c \
  "select service_line, count(*), count(open_date), count(close_date), count(serials_normalised) from case_ticket group by 1;"
```

## Watch out for

- **monday's daily API budget** is 1,000 calls on Free/Basic/Standard. A full
  sync costs roughly one call per 50 tickets per group, plus one per board for
  group discovery. Don't loop it.
- **The nightly scheduler stays off** unless `KPI_MONDAY_SYNC_ENABLED=true`.
  Leave it off locally; the manual endpoint works either way.
- `/actuator/health` requires auth in this app, so an unauthenticated probe
  returns 401, not a health body.
- Stop cleanly when done: `Ctrl-C`, then `docker stop raaspal-kpi-pg`. Never
  leave a local backend running against Supabase.
