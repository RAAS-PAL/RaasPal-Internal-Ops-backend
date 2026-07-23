package com.raaspal.robotrecommendation.telemetry.adapters.autoxing;

import com.fasterxml.jackson.databind.JsonNode;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingDeliveryReport;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingDeliveryReport.CategoryStat;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingDeliveryReport.DailyCount;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingDeliveryReport.LiveStatus;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingDeliveryReport.Summary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Builds an on-demand AutoXing delivery report for one robot and date range by
 * calling the statistics + live-state endpoints directly (no persistence). Handles
 * token expiry by re-authenticating and retrying once. The AutoXing statistics
 * endpoint caps the window at 30 days and caches results for 5 minutes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoxingReportService {

    /** Task categories returned by /statis/v2.0/task, excluding the "allStatis" rollup. */
    private static final List<String> CATEGORIES =
            List.of("call", "delivery", "other", "charging", "chassis", "disinfect");

    private static final int MAX_WINDOW_DAYS = 30;

    private final AutoxingApiClient apiClient;
    private final AutoxingAuthService authService;

    /**
     * Builds the delivery report for {@code robotId} over {@code [from, to]} (inclusive).
     * The range may not exceed 30 days (AutoXing statistics limit).
     */
    public AutoxingDeliveryReport build(String robotId, LocalDate from, LocalDate to) {
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
        LiveStatus liveStatus = null;
        boolean liveUnavailable = false;
        try {
            JsonNode state = withReauth(token -> apiClient.getRobotState(robotId, token));
            liveStatus = toLiveStatus(state);
        } catch (Exception e) {
            liveUnavailable = true;
            log.warn("AutoXing live state unavailable for {}: {}", robotId, e.getMessage());
        }

        return aggregate(robotId, from, to, stats, liveStatus, liveUnavailable);
    }

    /* ─── Aggregation ────────────────────────────────────────────────────────── */

    private AutoxingDeliveryReport aggregate(
            String robotId, LocalDate from, LocalDate to, JsonNode stats,
            LiveStatus liveStatus, boolean liveUnavailable) {

        List<CategoryStat> categories = new ArrayList<>();
        Map<String, Integer> dailyCounts = new TreeMap<>();
        int totalTasks = 0;
        double totalMileage = 0;
        long totalDurationSeconds = 0;

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
                String date = textField(row, "date");
                if (date != null) {
                    dailyCounts.merge(date, intField(row, "count", "taskCount"), Integer::sum);
                }
            }
            if (count == 0 && mileage == 0 && durationMs == 0) {
                continue;
            }
            long durationSeconds = durationMs / 1000;
            categories.add(new CategoryStat(category, count, round2(mileage), durationSeconds));
            totalTasks += count;
            totalMileage += mileage;
            totalDurationSeconds += durationSeconds;
        }

        List<DailyCount> daily = dailyCounts.entrySet().stream()
                .map(e -> new DailyCount(e.getKey(), e.getValue()))
                .toList();

        Summary summary = new Summary(totalTasks, round2(totalMileage), totalDurationSeconds);

        return new AutoxingDeliveryReport(
                robotId, periodLabel(from, to), liveStatus, summary, categories, daily,
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
                state.has("isCharging") ? state.path("isCharging").asBoolean() : null,
                state.has("isEmergencyStop") ? state.path("isEmergencyStop").asBoolean() : null,
                state.has("isManualMode") ? state.path("isManualMode").asBoolean() : null,
                state.has("isRemoteMode") ? state.path("isRemoteMode").asBoolean() : null,
                errors,
                textField(state, "areaId"),
                state.has("timestamp") ? state.path("timestamp").asLong() : null);
    }

    private static String buildNote(boolean liveUnavailable) {
        StringBuilder note = new StringBuilder(
                "Task counts, mileage and duration are aggregated from AutoXing daily statistics across all task "
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

    private String periodLabel(LocalDate from, LocalDate to) {
        // A single whole calendar month reads as "June 2026"; any other range as a span.
        if (from.getDayOfMonth() == 1
                && to.equals(from.withDayOfMonth(from.lengthOfMonth()))) {
            return Month.of(from.getMonthValue()).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + from.getYear();
        }
        return from + " to " + to;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
