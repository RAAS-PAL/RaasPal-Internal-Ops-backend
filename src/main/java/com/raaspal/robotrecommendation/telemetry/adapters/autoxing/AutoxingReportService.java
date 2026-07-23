package com.raaspal.robotrecommendation.telemetry.adapters.autoxing;

import com.fasterxml.jackson.databind.JsonNode;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingDeliveryReport;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingDeliveryReport.CategoryStat;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingDeliveryReport.DailyStat;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingDeliveryReport.LiveStatus;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingDeliveryReport.Summary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Builds an on-demand AutoXing delivery report for one robot and date range by
 * calling the statistics, live-state and directory endpoints directly (no
 * persistence). Handles token expiry by re-authenticating and retrying once.
 * The AutoXing statistics endpoint caps the window at 30 days and caches results
 * for 5 minutes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoxingReportService {

    /** Task categories returned by /statis/v2.0/task, excluding the "allStatis" rollup. */
    private static final List<String> CATEGORIES =
            List.of("call", "delivery", "other", "charging", "chassis", "disinfect");

    private static final int MAX_WINDOW_DAYS = 30;

    /** Business/building directories are account-wide and change rarely — cache briefly. */
    private static final Duration DIRECTORY_TTL = Duration.ofMinutes(10);

    private final AutoxingApiClient apiClient;
    private final AutoxingAuthService authService;

    private volatile JsonNode cachedBusinesses;
    private volatile JsonNode cachedBuildings;
    private volatile Instant directoryCachedAt = Instant.EPOCH;

    /** Robot identity and deployment context resolved from AutoXing's directories. */
    private record RobotContext(String robotName, String model, String customerName, String siteBranch) {
    }

    /**
     * Builds the delivery report for {@code robotId} over {@code [from, to]} (inclusive).
     * {@code robotNameOverride} / {@code modelOverride} are optional; when blank the
     * report falls back to AutoXing's own naming.
     */
    public AutoxingDeliveryReport build(String robotId, LocalDate from, LocalDate to,
                                        String robotNameOverride, String modelOverride) {
        if (!apiClient.isConfigured()) {
            throw new BadRequestException("AutoXing API credentials are not configured");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException("Report start date must not be after the end date");
        }
        if (from.plusDays(MAX_WINDOW_DAYS).isBefore(to)) {
            throw new BadRequestException("AutoXing report range cannot exceed " + MAX_WINDOW_DAYS + " days");
        }

        long startMs = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long endMs = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1;

        JsonNode stats;
        try {
            stats = withReauth(token -> apiClient.getTaskStatistics(List.of(robotId), startMs, endMs, token));
        } catch (AutoxingApiException e) {
            throw new BadRequestException("AutoXing statistics request failed: " + e.getMessage());
        }

        // Live status is best-effort — a robot can be offline while still having task history.
        JsonNode state = null;
        boolean liveUnavailable = false;
        try {
            state = withReauth(token -> apiClient.getRobotState(robotId, token));
        } catch (Exception e) {
            liveUnavailable = true;
            log.warn("AutoXing live state unavailable for {}: {}", robotId, e.getMessage());
        }

        RobotContext context = resolveContext(robotId, state, robotNameOverride, modelOverride);
        return aggregate(robotId, from, to, stats, toLiveStatus(state), liveUnavailable, context);
    }

    /* ─── Context resolution ─────────────────────────────────────────────────── */

    /**
     * Resolves the robot's display name, model, customer (business) and site
     * (building + area/floor) from AutoXing's directories. Every lookup is
     * best-effort — a failure degrades to "—" rather than failing the report.
     */
    private RobotContext resolveContext(String robotId, JsonNode state, String nameOverride, String modelOverride) {
        String businessId = text(state, "businessId");
        String areaId = text(state, "areaId");
        String buildingId = text(state, "buildingId");

        String autoxingModel = null;
        String autoxingName = null;
        try {
            JsonNode robot = withReauth(token -> apiClient.getRobotSummary(robotId, token));
            autoxingModel = blankToNull(robot.path("model").asText(null));
            autoxingName = blankToNull(robot.path("name").asText(null));
            if (businessId == null) {
                businessId = blankToNull(robot.path("businessId").asText(null));
            }
        } catch (Exception e) {
            log.warn("AutoXing robot lookup failed for {}: {}", robotId, e.getMessage());
        }

        // Area gives the zone name, floor, and (when state is unavailable) the buildingId.
        String areaName = null;
        Integer floor = null;
        try {
            JsonNode areas = AutoxingApiClient.entries(withReauth(token -> apiClient.getAreaList(robotId, token)));
            JsonNode area = pickArea(areas, areaId);
            if (area != null) {
                areaName = blankToNull(area.path("name").asText(null));
                floor = area.hasNonNull("floor") ? area.path("floor").asInt() : null;
                if (buildingId == null) {
                    buildingId = blankToNull(area.path("buildingId").asText(null));
                }
            }
        } catch (Exception e) {
            log.warn("AutoXing area lookup failed for {}: {}", robotId, e.getMessage());
        }

        String customerName = lookupName(directory(true), businessId);
        String siteName = lookupName(directory(false), buildingId);

        // "KUBOTA Precision Machinery · Floor 1" — fall back to the area name when the
        // building can't be resolved, so the report still says where the robot works.
        String siteBranch = siteName != null ? siteName : areaName;
        if (siteBranch != null && floor != null) {
            siteBranch = siteBranch + " · Floor " + floor;
        }

        String model = firstNonBlank(modelOverride, autoxingModel);
        String name = firstNonBlank(nameOverride, autoxingName,
                model != null ? "AutoXing " + model : "AutoXing Delivery Robot");

        return new RobotContext(name, model, orDash(customerName), orDash(siteBranch));
    }

    /** The area matching the robot's current areaId, else the first one listed. */
    private static JsonNode pickArea(JsonNode areas, String areaId) {
        if (!areas.isArray() || areas.isEmpty()) {
            return null;
        }
        if (areaId != null) {
            for (JsonNode area : areas) {
                if (areaId.equals(area.path("id").asText(null))) {
                    return area;
                }
            }
        }
        return areas.get(0);
    }

    /** Cached business (true) or building (false) directory; null when unavailable. */
    private JsonNode directory(boolean business) {
        if (Instant.now().isAfter(directoryCachedAt.plus(DIRECTORY_TTL))) {
            try {
                cachedBusinesses = AutoxingApiClient.entries(withReauth(apiClient::getBusinessList));
                cachedBuildings = AutoxingApiClient.entries(withReauth(apiClient::getBuildingList));
                directoryCachedAt = Instant.now();
            } catch (Exception e) {
                log.warn("AutoXing directory lookup failed: {}", e.getMessage());
            }
        }
        return business ? cachedBusinesses : cachedBuildings;
    }

    /** Finds the {@code name} of the entry whose {@code id} matches. */
    private static String lookupName(JsonNode entries, String id) {
        if (entries == null || !entries.isArray() || id == null) {
            return null;
        }
        for (JsonNode entry : entries) {
            if (id.equals(entry.path("id").asText(null))) {
                return blankToNull(entry.path("name").asText(null));
            }
        }
        return null;
    }

    /* ─── Aggregation ────────────────────────────────────────────────────────── */

    private AutoxingDeliveryReport aggregate(
            String robotId, LocalDate from, LocalDate to, JsonNode stats,
            LiveStatus liveStatus, boolean liveUnavailable, RobotContext context) {

        // Per-category rollups (delivery / charging / …) for the breakdown line.
        List<CategoryStat> categories = new ArrayList<>();
        int deliveryTasks = 0;
        for (String category : CATEGORIES) {
            JsonNode rows = stats.path(category);
            if (!rows.isArray() || rows.isEmpty()) {
                continue;
            }
            int count = 0;
            double mileage = 0;
            long durationMs = 0;
            for (JsonNode row : rows) {
                count += intField(row, "count", "taskCount");
                mileage += doubleField(row, "mileage", "taskMileage");
                durationMs += longField(row, "duration", "taskDuration");
            }
            if (count == 0 && mileage == 0 && durationMs == 0) {
                continue;
            }
            if ("delivery".equals(category)) {
                deliveryTasks = count;
            }
            categories.add(new CategoryStat(category, count, round2(mileage), durationMs / 1000));
        }

        // "allStatis" is AutoXing's own per-day total across categories — use it for the
        // daily table/chart and the period totals so the figures match their console.
        List<DailyStat> daily = new ArrayList<>();
        int totalTasks = 0;
        double totalMileage = 0;
        long totalDurationMs = 0;
        int activeDays = 0;
        String busiestDate = null;
        int busiestCount = 0;
        double busiestMileage = 0;
        long busiestDurationMs = 0;

        for (JsonNode row : stats.path("allStatis")) {
            String date = textField(row, "date");
            int count = intField(row, "count", "taskCount");
            double mileage = doubleField(row, "mileage", "taskMileage");
            long durationMs = longField(row, "duration", "taskDuration");

            daily.add(new DailyStat(date, count, round2(mileage), durationMs / 1000));
            totalTasks += count;
            totalMileage += mileage;
            totalDurationMs += durationMs;
            if (count > 0) {
                activeDays++;
            }
            if (count > busiestCount) {
                busiestCount = count;
                busiestDate = date;
                busiestMileage = mileage;
                busiestDurationMs = durationMs;
            }
        }

        // Fall back to the category sums if allStatis is absent.
        if (daily.isEmpty() && !categories.isEmpty()) {
            totalTasks = categories.stream().mapToInt(CategoryStat::count).sum();
            totalMileage = categories.stream().mapToDouble(CategoryStat::mileageMeters).sum();
            totalDurationMs = categories.stream().mapToLong(CategoryStat::durationSeconds).sum() * 1000;
        }

        int totalDays = (int) (to.toEpochDay() - from.toEpochDay()) + 1;
        long totalDurationSeconds = totalDurationMs / 1000;

        Summary summary = new Summary(
                totalTasks,
                deliveryTasks,
                totalTasks > 0 ? round2((double) deliveryTasks / totalTasks * 100) : 0,
                round2(totalMileage),
                totalDurationSeconds,
                activeDays,
                totalDays,
                activeDays > 0 ? round2((double) totalTasks / activeDays) : 0,
                totalTasks > 0 ? totalDurationSeconds / totalTasks : 0,
                activeDays > 0 ? round2(totalMileage / activeDays) : 0,
                busiestDate,
                busiestCount,
                round2(busiestMileage),
                busiestDurationMs / 1000);

        return new AutoxingDeliveryReport(
                robotId, context.robotName(), context.model(),
                context.customerName(), context.siteBranch(),
                periodLabel(from, to), liveStatus, summary, categories, daily,
                buildNote(liveUnavailable));
    }

    private LiveStatus toLiveStatus(JsonNode state) {
        if (state == null || state.isMissingNode() || state.isNull()) {
            return null;
        }
        // AutoXing errors are objects like {code, level, message, type}; surface the
        // human-readable message (falling back to the raw node for other shapes).
        List<String> errors = new ArrayList<>();
        JsonNode errorsNode = state.path("errors");
        if (errorsNode.isArray()) {
            for (JsonNode err : errorsNode) {
                if (err.isValueNode()) {
                    errors.add(err.asText());
                } else if (err.hasNonNull("message")) {
                    errors.add(err.path("message").asText());
                } else {
                    errors.add(err.toString());
                }
            }
        }
        return new LiveStatus(
                state.has("battery") ? state.path("battery").asInt() : null,
                textField(state, "moveState"),
                state.has("isOnline") ? state.path("isOnline").asBoolean() : null,
                state.has("isCharging") ? state.path("isCharging").asBoolean() : null,
                state.has("isEmergencyStop") ? state.path("isEmergencyStop").asBoolean() : null,
                state.has("isManualMode") ? state.path("isManualMode").asBoolean() : null,
                errors,
                state.has("timestamp") ? state.path("timestamp").asLong() : null);
    }

    private static String buildNote(boolean liveUnavailable) {
        StringBuilder note = new StringBuilder(
                "Task counts, distance and duration are aggregated from AutoXing daily statistics across all task "
                        + "categories. A per-task success/cancel breakdown is not provided by the statistics endpoint.");
        if (liveUnavailable) {
            note.append(" Live status is currently unavailable (the robot may be offline).");
        }
        return note.toString();
    }

    /**
     * Runs an API call with the current token; on an authentication failure, refreshes
     * the token once and retries. Non-auth failures propagate immediately.
     */
    private JsonNode withReauth(Function<String, JsonNode> call) {
        String token = authService.getValidToken();
        try {
            return call.apply(token);
        } catch (AutoxingApiException e) {
            if (!e.isAuthFailure()) {
                throw e;
            }
            log.info("AutoXing token rejected, re-authenticating and retrying once");
            return call.apply(authService.refresh());
        }
    }

    /* ─── Field/format helpers ───────────────────────────────────────────────── */

    private static int intField(JsonNode node, String... names) {
        JsonNode v = firstPresent(node, names);
        return v == null ? 0 : v.asInt();
    }

    private static long longField(JsonNode node, String... names) {
        JsonNode v = firstPresent(node, names);
        return v == null ? 0 : v.asLong();
    }

    private static double doubleField(JsonNode node, String... names) {
        JsonNode v = firstPresent(node, names);
        return v == null ? 0 : v.asDouble();
    }

    private static String textField(JsonNode node, String name) {
        JsonNode v = node.get(name);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** Same as {@link #textField} but tolerates a null node. */
    private static String text(JsonNode node, String name) {
        return node == null ? null : blankToNull(textField(node, name));
    }

    /** First of the candidate field names that is present and non-null on the node. */
    private static JsonNode firstPresent(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String v = blankToNull(value);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String orDash(String value) {
        return value == null ? "—" : value;
    }

    private String periodLabel(LocalDate from, LocalDate to) {
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

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
