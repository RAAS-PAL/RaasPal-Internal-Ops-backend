package com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto;

import java.util.List;

/**
 * On-demand delivery performance report for a single AutoXing robot over a date
 * range. Delivery-shaped (task counts, category breakdown, mileage, duration) plus
 * a snapshot of the robot's current live status. Distinct from the cleaning-oriented
 * {@code ReportPreviewResponse} because AutoXing robots do delivery, not cleaning.
 */
public record AutoxingDeliveryReport(
        String robotId,
        String periodLabel,
        LiveStatus liveStatus,        // null when the robot's live state could not be fetched
        Summary summary,
        List<CategoryStat> categories,
        List<DailyCount> daily,
        String note) {

    /** Snapshot of the robot's current state at report time. */
    public record LiveStatus(
            Integer batteryPct,
            String moveState,
            Boolean isCharging,
            Boolean isEmergencyStop,
            Boolean isManualMode,
            Boolean isRemoteMode,
            List<String> errors,
            String areaId,
            Long timestamp) {
    }

    /** Totals across all task categories for the period. */
    public record Summary(
            int totalTasks,
            double totalMileageMeters,
            long totalDurationSeconds) {
    }

    /** Per-category rollup (e.g. delivery, call, charging). */
    public record CategoryStat(
            String category,
            int count,
            double mileageMeters,
            long durationSeconds) {
    }

    /** Task count for a single day, for a simple trend. */
    public record DailyCount(String date, int count) {
    }
}
