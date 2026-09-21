package com.raaspal.robotrecommendation.telemetry.adapters.autoxing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raaspal.robotrecommendation.casereport.brand.BrandTicketQueryService;
import com.raaspal.robotrecommendation.casereport.brand.dto.BrandTicket;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingPerformanceReport;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingPerformanceReport.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The delivery-robot Executive Performance Report for one AutoXing robot, pulled live.
 *
 * <p>A prototype on the way to a stored, scheduled report: it reads AutoXing directly
 * (no persistence) so the layout can be checked against real numbers before a table is
 * designed around it. Three sources:
 * <ul>
 *   <li>{@code /task/v1.1/list} - one record per task: outcome, type, how it was started,
 *       when. Counts, completion, hours of day and failures come from here.</li>
 *   <li>{@code /statis/v2.0/task} - AutoXing's daily totals. The task list only carries
 *       <em>estimated</em> distance and time, so actual km and working hours come from here.</li>
 *   <li>The Service Tickets data already synced from monday - RAAS PAL cases on this serial.</li>
 * </ul>
 *
 * <p>Charging runs are not "work": a robot sent to its dock (run type 25, task type 1 or 7,
 * run type 33) is counted under Part 3's charging line and left out of the task counts,
 * completion rate and mix, which would otherwise credit the robot for plugging itself in.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoxingPerformanceService {

    static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    /** One calendar month. The task list has no documented cap; this keeps a pull to ~15 calls. */
    private static final int MAX_DAYS = 31;
    /** The statistics endpoint refuses windows over 30 days, so longer ranges are split. */
    private static final int STATS_CHUNK_DAYS = 30;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 60;

    private static final String TICKET_BRAND = "autoxing";

    private final AutoxingApiClient apiClient;
    private final AutoxingReportService reportService;
    private final BrandTicketQueryService ticketQuery;

    /** One task off the list, flags defaulted: AutoXing omits a flag rather than send false. */
    record TaskRow(String taskId, long createTime, Integer taskType, Integer runType, Integer sourceType,
                   boolean finished, boolean cancelled, boolean failed, String failureReason) {

        static TaskRow of(JsonNode n) {
            return new TaskRow(
                    n.path("taskId").asText(null),
                    n.path("createTime").asLong(0),
                    n.hasNonNull("taskType") ? n.path("taskType").asInt() : null,
                    n.hasNonNull("runType") ? n.path("runType").asInt() : null,
                    n.hasNonNull("sourceType") ? n.path("sourceType").asInt() : null,
                    n.path("isFinish").asBoolean(false),
                    n.path("isCancel").asBoolean(false),
                    n.path("isFailed").asBoolean(false),
                    blankToNull(n.path("failureReason").asText(null)));
        }

        boolean charging() {
            return Objects.equals(runType, 25) || Objects.equals(runType, 33)
                    || Objects.equals(taskType, 1) || Objects.equals(taskType, 7);
        }

        /** Failed beats cancelled beats finished - a failed task is also "finished". */
        Outcome outcome() {
            if (failed) return Outcome.FAILED;
            if (cancelled) return Outcome.CANCELLED;
            if (finished) return Outcome.COMPLETED;
            return Outcome.OPEN;
        }

        LocalDate date() {
            return Instant.ofEpochMilli(createTime).atZone(BANGKOK).toLocalDate();
        }

        int hour() {
            return Instant.ofEpochMilli(createTime).atZone(BANGKOK).getHour();
        }
    }

    enum Outcome { COMPLETED, CANCELLED, FAILED, OPEN }

    public AutoxingPerformanceReport build(String robotId, LocalDate from, LocalDate to,
                                           String nameOverride, String modelOverride,
                                           boolean includeServiceCases) {
        if (!apiClient.isConfigured()) {
            throw new BadRequestException("AutoXing API credentials are not configured");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException("Report start date must not be after the end date");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw new BadRequestException("The performance report covers at most " + MAX_DAYS + " days");
        }

        List<String> notes = new ArrayList<>();
        List<TaskRow> tasks;
        JsonNode stats;
        try {
            tasks = fetchTasks(robotId, from, to);
            stats = fetchStats(robotId, from, to);
        } catch (AutoxingApiException e) {
            throw new BadRequestException("AutoXing request failed: " + e.getMessage());
        }

        Integer previousCompleted = null;
        try {
            LocalDate prevTo = from.minusDays(1);
            LocalDate prevFrom = prevTo.minusDays(days - 1);
            previousCompleted = (int) fetchTasks(robotId, prevFrom, prevTo).stream()
                    .filter(t -> !t.charging() && t.outcome() == Outcome.COMPLETED).count();
        } catch (Exception e) {
            notes.add("previous_period_unavailable");
            log.warn("AutoXing previous-period pull failed for {}: {}", robotId, e.getMessage());
        }

        ServiceCases cases = null;
        if (includeServiceCases) {
            try {
                cases = serviceCases(robotId, from, to);
            } catch (Exception e) {
                notes.add("service_cases_unavailable");
                log.warn("Service cases unavailable for {}: {}", robotId, e.getMessage());
            }
        }

        AutoxingReportService.RobotContext context =
                reportService.resolveRobotContext(robotId, nameOverride, modelOverride);

        return assemble(robotId, from, to, tasks, stats, previousCompleted, cases,
                context.robotName(), context.model(), context.customerName(), context.siteBranch(), notes);
    }

    /* ─── Fetching ───────────────────────────────────────────────────────────── */

    private List<TaskRow> fetchTasks(String robotId, LocalDate from, LocalDate to) {
        long startMs = from.atStartOfDay(BANGKOK).toInstant().toEpochMilli();
        long endMs = to.plusDays(1).atStartOfDay(BANGKOK).toInstant().toEpochMilli() - 1;

        List<TaskRow> rows = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            int pageNum = page;
            JsonNode data = reportService.withReauth(
                    token -> apiClient.getTaskList(robotId, startMs, endMs, pageNum, PAGE_SIZE, token));
            JsonNode list = data.path("list");
            if (!list.isArray() || list.isEmpty()) break;
            list.forEach(n -> rows.add(TaskRow.of(n)));
            if (rows.size() >= data.path("count").asInt(Integer.MAX_VALUE)) break;
            if (page == MAX_PAGES) {
                log.warn("AutoXing task list for {} stopped at {} pages; report is incomplete", robotId, MAX_PAGES);
            }
        }
        return rows;
    }

    /** Daily statistics, split into windows the endpoint accepts, {@code allStatis} merged. */
    private JsonNode fetchStats(String robotId, LocalDate from, LocalDate to) {
        ArrayNode merged = JsonNodeFactory.instance.arrayNode();
        for (LocalDate start = from; !start.isAfter(to); start = start.plusDays(STATS_CHUNK_DAYS)) {
            LocalDate end = start.plusDays(STATS_CHUNK_DAYS - 1).isAfter(to) ? to : start.plusDays(STATS_CHUNK_DAYS - 1);
            long startMs = start.atStartOfDay(BANGKOK).toInstant().toEpochMilli();
            long endMs = end.plusDays(1).atStartOfDay(BANGKOK).toInstant().toEpochMilli() - 1;
            JsonNode chunk = reportService.withReauth(
                    token -> apiClient.getTaskStatistics(List.of(robotId), startMs, endMs, token));
            chunk.path("allStatis").forEach(merged::add);
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("allStatis", merged);
        return out;
    }

    private ServiceCases serviceCases(String robotId, LocalDate from, LocalDate to) {
        List<BrandTicket> mine = ticketQuery.all(ticketQuery.brand(TICKET_BRAND)).stream()
                .filter(t -> t.serial() != null && t.serial().equalsIgnoreCase(robotId.trim()))
                .filter(t -> {
                    LocalDate d = BrandTicketQueryService.ticketDate(t);
                    return d != null && !d.isBefore(from) && !d.isAfter(to);
                })
                .toList();
        List<Integer> action = mine.stream().map(BrandTicket::daysToAction).filter(Objects::nonNull).sorted().toList();
        Double median = action.isEmpty() ? null
                : action.size() % 2 == 1 ? (double) action.get(action.size() / 2)
                : (action.get(action.size() / 2 - 1) + action.get(action.size() / 2)) / 2.0;
        int open = (int) mine.stream().filter(BrandTicket::open).count();
        return ServiceCases.builder()
                .opened(mine.size())
                .resolved(mine.size() - open)
                .stillOpen(open)
                .medianDaysToAction(median)
                .build();
    }

    /* ─── Assembly (pure) ────────────────────────────────────────────────────── */

    static AutoxingPerformanceReport assemble(String robotId, LocalDate from, LocalDate to,
                                              List<TaskRow> tasks, JsonNode stats, Integer previousCompleted,
                                              ServiceCases cases, String robotName, String model,
                                              String customerName, String siteBranch, List<String> notes) {
        int totalDays = (int) ChronoUnit.DAYS.between(from, to) + 1;
        List<TaskRow> work = tasks.stream().filter(t -> !t.charging()).toList();
        List<TaskRow> charging = tasks.stream().filter(TaskRow::charging).toList();

        int completed = count(work, Outcome.COMPLETED);
        int cancelled = count(work, Outcome.CANCELLED);
        int failed = count(work, Outcome.FAILED);
        int ended = completed + cancelled + failed;
        Double completionRate = ended == 0 ? null : round1(100.0 * completed / ended);

        // Daily series, zero-filled across the whole period.
        Map<LocalDate, List<TaskRow>> byDay = work.stream().collect(Collectors.groupingBy(TaskRow::date));
        List<DailyPoint> daily = new ArrayList<>();
        int activeDays = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            List<TaskRow> day = byDay.getOrDefault(d, List.of());
            int done = count(day, Outcome.COMPLETED);
            daily.add(new DailyPoint(d, done, day.size() - done));
            if (!day.isEmpty()) activeDays++;
        }
        Double utilisation = round1(100.0 * activeDays / totalDays);

        // Hours of day and the busiest two-hour window.
        int[] hourly = new int[24];
        work.forEach(t -> hourly[t.hour()]++);
        Integer peakStart = null;
        int peakCount = 0;
        for (int h = 0; h < 23; h++) {
            int c = hourly[h] + hourly[h + 1];
            if (c > peakCount) {
                peakCount = c;
                peakStart = h;
            }
        }
        Double peakShare = work.isEmpty() ? null : round1(100.0 * peakCount / work.size());

        // Actual distance and working time from AutoXing's own daily totals.
        long durationMs = 0;
        double mileageM = 0;
        int statTasks = 0;
        for (JsonNode row : stats.path("allStatis")) {
            durationMs += row.path("duration").asLong(0);
            mileageM += row.path("mileage").asDouble(0);
            statTasks += row.path("count").asInt(0);
        }

        Double tasksChange = previousCompleted == null || previousCompleted == 0 ? null
                : round1(100.0 * (completed - previousCompleted) / previousCompleted);

        List<Reason> reasons = work.stream()
                .filter(t -> t.outcome() == Outcome.FAILED)
                .collect(Collectors.groupingBy(t -> t.failureReason() == null ? "" : t.failureReason(),
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(e -> new Reason(e.getKey(), e.getValue().intValue()))
                .toList();

        Summary summary = Summary.builder()
                .tasksCompleted(completed)
                .operatingSeconds(durationMs / 1000)
                .distanceKm(round1(mileageM / 1000.0))
                .completionRatePct(completionRate)
                .activeDays(activeDays)
                .totalDays(totalDays)
                .avgTasksPerActiveDay(activeDays == 0 ? null : round1((double) completed / activeDays))
                .previousTasksCompleted(previousCompleted)
                .tasksChangePct(tasksChange)
                .build();

        Operational operational = Operational.builder()
                .completionRatePct(completionRate)
                .utilisationPct(utilisation)
                .daily(daily)
                .hourly(Arrays.stream(hourly).boxed().toList())
                .peakHourStart(peakStart)
                .peakSharePct(peakShare)
                .taskMix(shares(work, t -> runTypeKey(t.runType())))
                .sources(shares(work, t -> sourceKey(t.sourceType())))
                .avgTaskSeconds(statTasks == 0 ? null : durationMs / 1000 / statTasks)
                .build();

        Reliability reliability = Reliability.builder()
                .totalTasks(work.size())
                .completed(completed)
                .cancelled(cancelled)
                .failed(failed)
                .cancelledPct(work.isEmpty() ? 0 : round1(100.0 * cancelled / work.size()))
                .failedPct(work.isEmpty() ? 0 : round1(100.0 * failed / work.size()))
                .topFailureReasons(reasons)
                .chargingSessions(charging.size())
                .chargingPerActiveDay(activeDays == 0 ? null : round1((double) charging.size() / activeDays))
                .build();

        return AutoxingPerformanceReport.builder()
                .robotId(robotId)
                .robotName(robotName)
                .model(model)
                .customerName(customerName)
                .siteBranch(siteBranch)
                .periodLabel(periodLabel(from, to))
                .from(from)
                .to(to)
                .summary(summary)
                .operational(operational)
                .reliability(reliability)
                .serviceCases(cases)
                .recommendations(recommend(summary, operational, reliability, cases))
                .notes(notes)
                .build();
    }

    /**
     * Rule-based, like the Gausium report: every rule that fires contributes one line, and
     * a clean month says so. Thresholds are first guesses to be tuned with the RE team.
     */
    static List<Recommendation> recommend(Summary s, Operational o, Reliability r, ServiceCases cases) {
        List<Recommendation> out = new ArrayList<>();
        if (r.totalTasks() == 0) {
            out.add(new Recommendation("NO_DATA", Map.of()));
            return out;
        }
        if (s.completionRatePct() != null && s.completionRatePct() < 90) {
            out.add(new Recommendation("LOW_COMPLETION", Map.of("value", s.completionRatePct())));
        }
        if (r.cancelledPct() >= 10) {
            out.add(new Recommendation("HIGH_CANCEL", Map.of("value", r.cancelledPct(), "count", r.cancelled())));
        }
        if (r.failed() > 0) {
            String top = r.topFailureReasons().isEmpty() ? "" : r.topFailureReasons().get(0).reason();
            out.add(new Recommendation("FAILURES", Map.of("count", r.failed(), "reason", top)));
        }
        if (o.utilisationPct() != null && o.utilisationPct() < 80) {
            out.add(new Recommendation("IDLE_DAYS",
                    Map.of("idle", s.totalDays() - s.activeDays(), "total", s.totalDays())));
        }
        if (o.peakHourStart() != null && o.peakSharePct() != null && o.peakSharePct() >= 30) {
            out.add(new Recommendation("PEAK_HOURS", Map.of(
                    "start", o.peakHourStart(), "end", o.peakHourStart() + 2, "value", o.peakSharePct())));
        }
        if (s.tasksChangePct() != null && s.tasksChangePct() <= -20) {
            out.add(new Recommendation("USAGE_DROP", Map.of("value", s.tasksChangePct())));
        }
        if (cases != null && cases.stillOpen() > 0) {
            out.add(new Recommendation("OPEN_SERVICE_CASES", Map.of("count", cases.stillOpen())));
        }
        if (out.isEmpty()) {
            out.add(new Recommendation("HEALTHY", Map.of()));
        }
        return out;
    }

    /* ─── Labels ─────────────────────────────────────────────────────────────── */

    /** AutoXing's run types (public spec, 2026-09-21), as stable keys for translation. */
    static String runTypeKey(Integer runType) {
        if (runType == null) return "other";
        return switch (runType) {
            case 20 -> "quick_delivery";
            case 21 -> "multi_point";
            case 22 -> "direct_delivery";
            case 23 -> "roaming";
            case 24 -> "return";
            case 26 -> "summon";
            case 27 -> "birthday";
            case 28 -> "guiding";
            case 29 -> "lifting";
            case 30 -> "lifting_cruise";
            case 31 -> "flexible_carry";
            case 32 -> "roll";
            case 0, 1 -> "disinfection";
            default -> "other";
        };
    }

    static String sourceKey(Integer sourceType) {
        if (sourceType == null) return "unknown";
        return switch (sourceType) {
            case 1 -> "robot_screen";
            case 2 -> "mini_program";
            case 3 -> "pager";
            case 4 -> "chassis";
            case 5 -> "dispatch";
            case 6 -> "integration";
            case 7 -> "pad_app";
            default -> "unknown";
        };
    }

    private static List<Share> shares(List<TaskRow> rows, Function<TaskRow, String> key) {
        if (rows.isEmpty()) return List.of();
        return rows.stream().collect(Collectors.groupingBy(key, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new Share(e.getKey(), e.getValue().intValue(), round1(100.0 * e.getValue() / rows.size())))
                .toList();
    }

    private static int count(List<TaskRow> rows, Outcome outcome) {
        return (int) rows.stream().filter(t -> t.outcome() == outcome).count();
    }

    static String periodLabel(LocalDate from, LocalDate to) {
        if (from.getDayOfMonth() == 1 && to.equals(from.withDayOfMonth(from.lengthOfMonth()))) {
            return Month.of(from.getMonthValue()).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + from.getYear();
        }
        return from + " to " + to;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
