package com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto;

import lombok.Builder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The Executive Robot Performance Report for one AutoXing delivery robot - the Gausium
 * report's frame (header, Part 1-3, Recommendations) with delivery-robot content.
 *
 * <p>Labels are sent as stable keys ({@code multi_point}, {@code robot_screen},
 * {@code HIGH_CANCEL}) so the console translates them; only the robot's own failure
 * text is passed through as-is.
 */
@Builder
public record AutoxingPerformanceReport(
        String robotId,
        String robotName,
        String model,
        String customerName,
        String siteBranch,
        String periodLabel,
        LocalDate from,
        LocalDate to,
        Summary summary,
        Operational operational,
        Reliability reliability,
        /** Null when service cases were not requested or could not be read. */
        ServiceCases serviceCases,
        /** Recorded faults (robot_fault_event); null when the history could not be read. */
        AutoxingFaultSummary faults,
        List<Recommendation> recommendations,
        List<String> notes
) {

    /** Part 1. Distance and hours are AutoXing's actuals; counts come from the task list. */
    @Builder
    public record Summary(
            int tasksCompleted,
            long operatingSeconds,
            double distanceKm,
            /** completed / (completed + cancelled + failed); null with no ended tasks. */
            Double completionRatePct,
            int activeDays,
            int totalDays,
            Double avgTasksPerActiveDay,
            /** Completed tasks in the previous period of the same length; null if unreadable. */
            Integer previousTasksCompleted,
            Double tasksChangePct
    ) {
    }

    /** Part 2. */
    @Builder
    public record Operational(
            Double completionRatePct,
            /** active days / days in the period. */
            Double utilisationPct,
            List<DailyPoint> daily,
            /** Tasks created per hour of day, Bangkok time, index 0-23. */
            List<Integer> hourly,
            /** Start hour of the busiest two-hour window, or null with no tasks. */
            Integer peakHourStart,
            /** Share of all tasks that fell inside the peak window. */
            Double peakSharePct,
            List<Share> taskMix,
            List<Share> sources,
            /** Actual working time / tasks counted by AutoXing's statistics. */
            Long avgTaskSeconds
    ) {
    }

    public record DailyPoint(LocalDate date, int completed, int notCompleted) {
    }

    public record Share(String key, int count, double pct) {
    }

    /** Part 3. */
    @Builder
    public record Reliability(
            int totalTasks,
            int completed,
            int cancelled,
            int failed,
            double cancelledPct,
            double failedPct,
            List<Reason> topFailureReasons,
            int chargingSessions,
            Double chargingPerActiveDay
    ) {
    }

    public record Reason(String reason, int count) {
    }

    /** RAAS PAL service tickets on this robot's serial, opened in the period. */
    @Builder
    public record ServiceCases(int opened, int resolved, int stillOpen, Double medianDaysToAction) {
    }

    /** A rule that fired, with the numbers its sentence needs. */
    public record Recommendation(String code, Map<String, Object> params) {
    }
}
