# Testing the RE KPI monday.com sync locally

Everything below runs against a **throwaway docker Postgres**, never Supabase.
That matters: local dev and production share one Supabase database, so starting
the backend against it would apply **V38–V40** to production.

## One-time setup (already done on this Mac; listed so it can be rebuilt)

```bash
docker run -d --name raaspal-kpi-pg \
  -e POSTGRES_PASSWORD=raaspal -e POSTGRES_DB=robot_recommendation_db \
  -p 5433:5432 postgres:16
```

`src/main/resources/application-local.properties` (gitignored — keep it so) points
at that database, sets `server.port=8081` because **Jenkins owns port 8080** on this
machine, and holds `app.monday.api.token`. The token file is the only copy; it is
never committed and never echoed. If it ever appears in a screenshot or a chat,
rotate it in monday (Admin → API).

## Each session

```bash
docker start raaspal-kpi-pg
cd ~/Desktop/RassPal/RaasPal-Internal-Ops-backend
git checkout feat/re-kpi-dashboard
sh mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

`sh mvnw`, not `./mvnw` — the wrapper is not executable here and `mvn` is not on PATH.
Wait for `Started RobotRecommendationApiApplication`; Flyway applies any new
migration to the local database on the way up.

Console (separate terminal), pointed at the local API rather than Render:

```bash
cd ~/Desktop/RassPal/RaasPal-Ops-frontend
BACKEND_PROXY_TARGET=http://localhost:8081 npm run dev
```

Log in for a bearer token — the seeded admin, created on first start. This is the
**app** token, unrelated to the monday one:

```bash
TOKEN=$(curl -s -X POST localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@raaspal.com","password":"Admin@1234"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["accessToken"])')
H="Authorization: Bearer $TOKEN"
```

Environment variables do not cross terminals: set `TOKEN` in the terminal that runs
the curls, or every call answers 401.

## The boards, and the rule for reading them

| Board | id | Ticket type | Serial column |
|---|---|---|---|
| Cleaning Tickets | `3451717331` | CM | `text0` |
| Delivery Tickets | `1647612496` | CM | `tags42` |
| Installation Tickets | `3109668017` | INSTALLATION | `text33` |

Every column id in `app.kpi.monday.boards[*]` was verified against the live boards on
2026-09-08. **The S/N is the foreign key across all three**: an installation board
row says nothing about whether the robot is cleaning or delivery, so its serial is
looked up on the two single-line CM boards.

**Strict rule: never pull ticket rows into a transcript, log or scratch file.**
Schema, column names, status labels and counts only. The endpoints that respect it:

```bash
curl -s -H "$H" localhost:8081/api/v1/kpi/monday/boards            # every board: id, name, state
curl -s -H "$H" localhost:8081/api/v1/kpi/monday/boards/3109668017 # columns (+ status labels), groups — no rows
curl -s -H "$H" localhost:8081/api/v1/kpi/monday/config            # effective mapping; never the token
```

`/api/v1/case-reports/monday/preview` returns rows. Do not use it for this work.

## The formulas (RE team, 2026-09-08)

| KPI | Rule | Config |
|---|---|---|
| 1st Time Install | later date of the TimeLine, +30 days; any CM naming the same S/N → 0 | `install-follow-up-days=30` |
| First Time Fix | another CM naming the same S/N within 14 days → 0 | `repeat-window-days=14` |
| SLA | RE Action date within 7 days of Open Date | `boards[*].sla-days=7` |

Blank serial → counted as success and reported as `withoutSerial`. Unclassifiable
installation → fleet total only, reported as `unclassifiedTickets`. No RE Action
date → `slaUnknown`, never a breach. Neither ticket board has a close-date column;
SLA is time-to-first-action by design.

**Not every board row is a KPI case.** Each board may name a category column and
the values that count (`columns.category` + `include-categories`; the token
`(blank)` means an empty cell counts). Rows outside the list are synced and
archived but left out, and reported as `excludedByCategory`. Current lists:

| Board | Category column | Counted |
|---|---|---|
| Cleaning | `color_mkyj4ncq` Type of case | Incident case, Service case, Request case, (blank) — reproduces the deck's 643 (live 639); parts shipments are out |
| Delivery | `color_mkyh88bs` Type of Case | everything — the deck's 815 equals the whole board |
| Installation | `status` Job Type | Installation, Install mapping, Mapping & Training, Mapping, Plans, DONE — **provisional**, see below |

A follow-up CM is still a follow-up whatever its category: a parts shipment for
the same serial is evidence the robot came back.

## Sync and read

```bash
curl -s -X POST -H "$H" localhost:8081/api/v1/kpi/monday/sync                       # 202, background
curl -s -H "$H" localhost:8081/api/v1/kpi/monday/sync/status | python3 -m json.tool  # poll until running=false
curl -s -H "$H" localhost:8081/api/v1/kpi/monday/sync/runs   | python3 -m json.tool  # one row per board per run
curl -s -H "$H" 'localhost:8081/api/v1/kpi/cm-cases?from=2026-01&to=2026-06' | python3 -m json.tool
```

A full first sync of the three boards is about five minutes and about 150 API
calls (page size 100). The backend log prints a line per page, so a board that is
reading looks like it. Later syncs land as updates. Verify by counting, never by
selecting:

```bash
docker exec raaspal-kpi-pg psql -U postgres -d robot_recommendation_db -c \
  "select source_board_id, ticket_type, count(*), count(serials_normalised), count(action_date), count(install_date) from case_ticket group by 1,2 order by 1;"
```

Expected against the Jan–Jun 2026 deck: delivery CM 815 (live 816), cleaning CM
643 (live 639 with the category filter), FTF 72.3% (live ≈75%).

## Known limits

- Only ~177 of 833 installation tickets record a serial, so 1st Time Install is
  truly measured for about a fifth of installs. That is how the board is filled,
  not a code fault.
- "Installation Tickets" is really the RE team's job board (25 job types). The
  include list above keeps the install-shaped ones, but for Jan–Jun 2026 that is
  still 59 rows (Plans 26, Mapping & Training 24, Installation 6, DONE 2, Install
  mapping 1) against the deck's 23, and no subset of labels gives 23. Which job
  types the RE team counts as an installation is an open question.
- PM Complete and CSAT are not shown. PM is sourceable (`PM Yip-upload` 2957857962
  for visits; `PM Cleaning` 2048972900 / `PM Delivery` 4129404143 for contracts)
  once the visits-due-per-robot rule is known.
- monday's daily API budget is 1,000 calls on Free/Basic/Standard. Don't loop syncs.
- `/actuator/health` needs auth here; an unauthenticated probe returns 401.
- Stop cleanly: `Ctrl-C`, then `docker stop raaspal-kpi-pg`. Never leave a local
  backend running against Supabase.
