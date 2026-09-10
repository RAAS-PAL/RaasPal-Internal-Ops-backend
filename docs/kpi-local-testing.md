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
| Cleaning | `color_mkyj4ncq` Type of case | everything — a parts-shipping filter reproduces the deck's total (639 vs 643) only by coincidence; the deck's monthly labels match the *unfiltered* rows for Jan/Feb/Mar/Jun |
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

Expected against the Jan–Jun 2026 deck: delivery CM 815 (live 816, every month
within one); cleaning CM 643 (live 756 — Jan/Feb/Mar/Jun match within two, May is
deck 38 vs live 133 and is an open question for the RE team); FTF 72.3% (live ≈75%).

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

## CSAT — the survey workbooks

CSAT is the one RE KPI with no monday source. It comes from the post-job phone
survey, which the RE team tallies by hand into **four workbooks** — installation,
MA (= PM), CM cleaning, CM delivery — and replaces about once a month. Only the
month sheets (`Jan 2026`, `June 2026`, …) are read, by label; the `Detail_` and
summary sheets that name customers are never opened. Nothing is persisted and
nothing is scheduled; a replaced file is noticed on the next request.

**The rule: where a cell exists, show the cell; where the deck had to combine
sheets, combine them the deck's way.**

- One survey, one month → the sheet's own **Top Box** cell (`I17 = AVERAGE(Q10:Q14)`,
  the RE team's formula), found by the "Top Box" label, read as it is. Never
  recomputed.
- A survey over a range, or all surveys in a month → no sheet holds it. Combined as
  the deck combines them: all ratings of 5 over all ratings given, from the I and N
  columns of the question rows. Every deck figure reproduces exactly.
- Response rate → responses ÷ customers called (`ประเมินผล` ÷ `# ลูกค้า`).

Point the backend at the folder (the `local` profile already does; blank = not
configured, and `GET /kpi/csat` is a 400 naming the variable):

```properties
app.kpi.csat.folder=/Users/kusk/Downloads/csatscorefordashboard   # or KPI_CSAT_FOLDER=…
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "localhost:8081/api/v1/kpi/csat?from=2026-01&to=2026-06" \
  | jq '.data.totals | to_entries[] | {(.key): .value.topBoxRate}'
# expect overall 86.2, installation 79.2, pm 91.9, cleaning 70.3, delivery 89.7 — the deck's CSAT slide

curl -s -H "Authorization: Bearer $TOKEN" localhost:8081/api/v1/kpi/csat/source | jq .data      # files, surveys, months
curl -s -X POST -H "Authorization: Bearer $TOKEN" localhost:8081/api/v1/kpi/csat/reload | jq .data   # after dropping new files
```

`warnings` lists only what could not be read: a month sheet missing a tally, a Top
Box cell or the rating header; a file whose survey could not be told; a survey with
no workbook.

When the workbooks move to a bucket, implement `kpi.csat.CsatWorkbookSource` for it
and nothing else changes.

## Excel export — for the deck

Both live areas download as an .xlsx **carrying the page's own charts** as real
Excel chart objects, so one can be copied straight into PowerPoint and stay
editable there: the numbers can be corrected and the colours are a click. A
picture of an HTML chart is neither.

The charts mirror the panels — same type, colours and average rule. CSAT's Top
Box sheet holds five (the overall panel, then each survey); the report holds one
per panel sheet: 1st Time Install, Total CM Cases (stacked), First Time Fix
(cleaning against delivery) and SLA (within/over stacked).

```bash
curl -s -H "Authorization: Bearer $TOKEN" -OJ \
  "localhost:8081/api/v1/kpi/cm-cases/export?from=2026-01&to=2026-06"   # re-kpi-report_2026-01_2026-06.xlsx
curl -s -H "Authorization: Bearer $TOKEN" -OJ \
  "localhost:8081/api/v1/kpi/csat/export?from=2026-01&to=2026-06"       # re-kpi-csat_2026-01_2026-06.xlsx
```

The layout is what makes Insert Chart work without a range fight: **header on row
1, data from row 2, nothing above it, nothing merged**, months down the rows and
series across the columns. Rates are real percentage cells (0.792 shown as
79.2%), so the chart axis is a percentage axis. A month a survey did not run is
**blank, not zero** — a gap in a chart reads as "not surveyed"; a zero bar reads
as "nobody was happy".

Provenance lives on the `About` sheet rather than above the tables: source
workbooks, how far they run, the window lengths, each definition in words, and
for CSAT whether a figure is a `sheet cell` or was `pooled`. Anything written
above a data block is what breaks Excel's series detection, which is why it is
not there.

### Writing charts with POI — the four traps

All of them fail silently or as "Excel found a problem with some content", so
`KpiXlsxExportTest` validates every chart's XML against the schema
(`CTChart.validate()`), which names the offending element instead.

1. **`poi-ooxml-lite` cannot write a stacked chart.** It ships the generated
   classes but not every compiled schema resource, and `<c:overlap>` — which a
   stacked column chart needs, or Excel draws the series side by side — dies
   with *Could not locate compiled schema resource … stoverlappercent….xsb*.
   The pom therefore excludes lite and depends on `poi-ooxml-full` (~14 MB).
2. **A `<c:lineChart>` must declare `<c:grouping>`; POI writes none.** The
   average rule is a flat line series, so every panel with a rule was invalid
   until `setGrouping(Grouping.STANDARD)`.
3. **POI marks a number format source-linked**, which tells the reader to ignore
   the format code — a rate axis then renders 0 to 1, and data labels print
   `0.682`. Both `numFmt`s are written with `sourceLinked="false"`, and the
   labels get their own (the schema wants it as `dLbls`' first child).
4. **POI's value axis writes `<c:crossBetween val="midCat"/>`**, which plots the
   first and last category on the plot area's own edges. Excel then draws half of
   each of those two bars outside the plot and clips it: the file opens with the
   first and last month shaved down their outer side, and nothing in the XML
   looks wrong. `setCrossBetween(AxisCrossBetween.BETWEEN)` gives each month a
   band, which is Excel's own default for a column chart. It shortens the average
   rule to the outermost months' centres — the price of whole bars.

The rule's own figure is printed on one point of the line, and which point is
chosen by reading the bars back out of the cells: the last month whose bar is
clear of the rule, or the furthest one if none is. Two numbers at the same height
are unreadable, and a chart cannot be told to move a bar's label out of the way
the way the page does.

`XDDFLineProperties.setWidth` is in **points**, not EMU: 20000 there is 254
million EMU and outside `ST_LineWidth`.
