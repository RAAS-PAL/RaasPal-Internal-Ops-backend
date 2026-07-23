package com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto;

import java.util.List;

/**
 * On-demand delivery performance report for a single AutoXing robot over a date
 * range. Delivery-shaped (task counts, category breakdown, distance, duration)
 * rather than the cleaning-oriented {@code ReportPreviewResponse}, because
 * AutoXing robots do delivery, not cleaning.
 *
 * <p>{@code liveStatus} is a "right now" snapshot for the operator UI — it is
 * deliberately NOT part of the printable period report, which must describe only
 * the reporting window.
 */
public record AutoxingDeliveryReport(
        String robotId,
        String robotName,       // custom name if supplied, else "AutoXing <model>"
        String model,           // supplied model (e.g. "D-150"), else AutoXing's category
        String customerName,    // resolved from businessId
        String siteBranch,      // resolved from buildingId (+ area/floor)
        String periodLabel,     // e.g. "1–20 July 2026"
        LiveStatus liveStatus,  // null when the robot's live state could not be fetched
        Summary summary,
        List<CategoryStat> categories,
        List<DailyStat> daily,
        String note) {

    /** Snapshot of the robot's state at report time — for the operator UI only. */
    public record LiveStatus(
            Integer batteryPct,
            String moveState,
            Boolean isOnline,
            Boolean isCharging,
            Boolean isEmergencyStop,
            Boolean isManualMode,
            List<String> errors,
            Long timestamp) {
    }

    /** Period totals and derived figures the report renders directly. */
    public record Summary(
            int totalTasks,
            int deliveryTasks,
            double deliverySharePct,
            double totalMileageMeters,
            long totalDurationSeconds,
            int activeDays,
            int totalDays,
            double tasksPerActiveDay,
            long avgTaskSeconds,
            double avgMileagePerActiveDayMeters,
            String busiestDate,
            int busiestCount,
            double busiestMileageMeters,
            long busiestDurationSeconds) {
    }

    /** Per-category rollup (delivery, call, charging, chassis, disinfect, other). */
    public record CategoryStat(
            String category,
            int count,
            double mileageMeters,
            long durationSeconds) {
    }

    /** One day of activity, for the daily table and volume chart. */
    public record DailyStat(
            String date,
            int count,
            double mileageMeters,
            long durationSeconds) {
    }
}
