package com.raaspal.robotrecommendation.telemetry.adapters.pudu;

import com.fasterxml.jackson.databind.JsonNode;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.telemetry.adapters.pudu.dto.PuduDeliveryReport;
import com.raaspal.robotrecommendation.telemetry.adapters.pudu.dto.PuduDeliveryReport.CategoryStat;
import com.raaspal.robotrecommendation.telemetry.adapters.pudu.dto.PuduDeliveryReport.DailyStat;
import com.raaspal.robotrecommendation.telemetry.adapters.pudu.dto.PuduDeliveryReport.PreviousPeriod;
import com.raaspal.robotrecommendation.telemetry.adapters.pudu.dto.PuduDeliveryReport.Summary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds an on-demand delivery report for one PUDU robot — the AutoXing preview's
 * counterpart, with no persistence, no adapter and no scheduler.
 *
 * <p>Source: the data-board's delivery statistics list, asked for one row per robot per
 * day and filtered to the requested serial. Everything on the report — name, model,
 * store, the day-by-day figures — comes out of those rows, so there are no directory
 * calls to make. The previous period is the same query shifted back by the window's
 * length, so the comparison is per robot, not per store.
 *
 * <p>Days are cut in Bangkok time, which is what the customer's day is. PUDU accepts the
 * offset as whole hours; Thailand has no daylight saving, so it is always 7.
 *
 * <p>Why this does not implement {@code TelemetryAdapter}: that contract promises
 * per-task rows with an external task id to dedupe on, and PUDU's data-board offers
 * neither — it is aggregates only. Persisting PUDU (phase 2) means per-robot-per-day
 * rows keyed on {@code (sn, date)}, a different table, not this pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PuduReportService {

    /** Over 31 days the period label stops meaning anything, and the paging cost grows with it. */
    private static final int MAX_WINDOW_DAYS = 31;

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    private final PuduApiClient apiClient;

    /**
     * @param sn           the robot's serial number, as PUDU reports it
     * @param from         first day, inclusive
     * @param to           last day, inclusive; the window may not exceed 31 days
     * @param shopId       narrows the query to one PUDU store when known — fewer pages,
     *                     same result; null reads the whole account
     * @param customerName printed as the customer when supplied; else the store name
     */
    public PuduDeliveryReport build(String sn, LocalDate from, LocalDate to, Long shopId, String customerName) {
        if (!apiClient.isConfigured()) {
            throw new BadRequestException("PUDU API credentials are not configured");
        }
        if (sn == null || sn.isBlank()) {
            throw new BadRequestException("A robot serial number is required");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException("Report start date must not be after the end date");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_WINDOW_DAYS) {
            throw new BadRequestException("PUDU report range cannot exceed " + MAX_WINDOW_DAYS + " days");
        }

        String serial = sn.trim();
        List<JsonNode> rows;
        List<JsonNode> previousRows;
        long length = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate prevFrom = from.minusDays(length);
        LocalDate prevTo = from.minusDays(1);
        try {
            rows = rowsFor(serial, from, to, shopId);
            previousRows = rowsFor(serial, prevFrom, prevTo, shopId);
        } catch (PuduApiException e) {
            if (e.isAuthFailure()) {
                throw new BadRequestException("PUDU rejected the API key or signature: " + e.getMessage());
            }
            throw new BadRequestException("PUDU statistics request failed: " + e.getMessage());
        }

        // Identity from the rows themselves; the first row is as good as any.
        JsonNode any = rows.isEmpty() ? (previousRows.isEmpty() ? null : previousRows.get(0)) : rows.get(0);
        String model = any == null ? null : text(any, "product_name");
        String nickname = any == null ? null : text(any, "robot_name");
        String shop = any == null ? null : text(any, "shop_name");
        String robotName = nickname != null ? nickname : "PUDU " + (model == null ? "robot" : model);

        // One entry per day of the period, idle days at zero, so the chart has a full axis.
        Map<LocalDate, double[]> byDay = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            byDay.put(d, new double[3]); // tasks, km, hours
        }
        int tables = 0;
        int trays = 0;
        for (JsonNode row : rows) {
            LocalDate day = day(row);
            if (day == null || !byDay.containsKey(day)) continue;
            double[] acc = byDay.get(day);
            acc[0] += row.path("task_count").asInt(0);
            acc[1] += row.path("mileage").asDouble(0);
            acc[2] += row.path("duration").asDouble(0);
            tables += row.path("table_count").asInt(0);
            trays += row.path("tray_count").asInt(0);
        }

        List<DailyStat> daily = new ArrayList<>(byDay.size());
        int totalTasks = 0;
        double totalMeters = 0;
        long totalSeconds = 0;
        int activeDays = 0;
        DailyStat busiest = null;
        for (Map.Entry<LocalDate, double[]> e : byDay.entrySet()) {
            int count = (int) Math.round(e.getValue()[0]);
            double meters = kmToMeters(e.getValue()[1]);
            long seconds = hoursToSeconds(e.getValue()[2]);
            DailyStat stat = new DailyStat(e.getKey().toString(), count, meters, seconds);
            daily.add(stat);
            totalTasks += count;
            totalMeters += meters;
            totalSeconds += seconds;
            if (count > 0) activeDays++;
            if (busiest == null || count > busiest.count()) busiest = stat;
        }
        int totalDays = daily.size();

        Summary summary = new Summary(
                totalTasks,
                totalTasks,
                totalTasks > 0 ? 100.0 : 0.0,
                round1(totalMeters),
                totalSeconds,
                activeDays,
                totalDays,
                activeDays == 0 ? 0 : round2((double) totalTasks / activeDays),
                totalTasks == 0 ? 0 : totalSeconds / totalTasks,
                activeDays == 0 ? 0 : round1(totalMeters / activeDays),
                busiest == null || busiest.count() == 0 ? null : busiest.date(),
                busiest == null ? 0 : busiest.count(),
                busiest == null ? 0 : busiest.mileageMeters(),
                busiest == null ? 0 : busiest.durationSeconds());

        List<CategoryStat> categories = List.of(
                new CategoryStat("delivery", totalTasks, round1(totalMeters), totalSeconds));

        PreviousPeriod previous = previousPeriod(previousRows, prevFrom, prevTo);
        Double avgSpeed = totalSeconds == 0 ? null : round2(totalMeters / totalSeconds);
        PuduDeliveryReport.Pudu pudu = new PuduDeliveryReport.Pudu(tables, trays, avgSpeed, previous);

        String note = rows.isEmpty()
                ? "PUDU reported no delivery statistics for " + serial + " in this period. Either the "
                  + "robot was idle, or the serial is not on this account"
                  + (shopId == null ? "." : " or not in store " + shopId + ".")
                : null;

        log.info("PUDU delivery report for {} {}..{}: {} rows, {} tasks over {} active days",
                serial, from, to, rows.size(), totalTasks, activeDays);

        return new PuduDeliveryReport(
                serial, robotName, model,
                customerName != null && !customerName.isBlank() ? customerName.trim() : shop,
                shop, periodLabel(from, to),
                summary, categories, daily, pudu, note);
    }

    /** The robot's rows for a window — every robot's day comes back; keep this serial's. */
    private List<JsonNode> rowsFor(String serial, LocalDate from, LocalDate to, Long shopId) {
        long start = from.atStartOfDay(BUSINESS_ZONE).toEpochSecond();
        long end = to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toEpochSecond() - 1;
        int offsetHours = BUSINESS_ZONE.getRules().getOffset(from.atStartOfDay(BUSINESS_ZONE).toInstant())
                .getTotalSeconds() / 3600;

        List<JsonNode> mine = new ArrayList<>();
        for (JsonNode row : apiClient.deliveryRows(start, end, offsetHours, shopId, "robot", "day")) {
            if (serial.equalsIgnoreCase(text(row, "sn"))) {
                mine.add(row);
            }
        }
        return mine;
    }

    private PreviousPeriod previousPeriod(List<JsonNode> rows, LocalDate from, LocalDate to) {
        int tasks = 0, tables = 0, trays = 0;
        double km = 0, hours = 0;
        for (JsonNode row : rows) {
            tasks += row.path("task_count").asInt(0);
            tables += row.path("table_count").asInt(0);
            trays += row.path("tray_count").asInt(0);
            km += row.path("mileage").asDouble(0);
            hours += row.path("duration").asDouble(0);
        }
        return new PreviousPeriod(periodLabel(from, to), tasks, round1(kmToMeters(km)),
                hoursToSeconds(hours), tables, trays);
    }

    /** {@code task_time} is {@code Y-m-d} for day buckets; anything else is not a day row. */
    private static LocalDate day(JsonNode row) {
        String t = text(row, "task_time");
        if (t == null || t.length() < 10) return null;
        try {
            return LocalDate.parse(t.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }

    private static double kmToMeters(double km) {
        return km * 1000.0;
    }

    private static long hoursToSeconds(double hours) {
        return Math.round(hours * 3600.0);
    }

    private static String periodLabel(LocalDate from, LocalDate to) {
        // A whole calendar month reads as "June 2026"; any other range as a day span.
        if (from.getDayOfMonth() == 1 && to.equals(from.withDayOfMonth(from.lengthOfMonth()))) {
            return Month.of(from.getMonthValue()).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + from.getYear();
        }
        String month = Month.of(to.getMonthValue()).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        if (from.getMonthValue() == to.getMonthValue() && from.getYear() == to.getYear()) {
            return from.getDayOfMonth() + "–" + to.getDayOfMonth() + " " + month + " " + to.getYear();
        }
        return from + " to " + to;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
