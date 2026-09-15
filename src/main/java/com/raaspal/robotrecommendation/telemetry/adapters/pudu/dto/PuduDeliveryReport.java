package com.raaspal.robotrecommendation.telemetry.adapters.pudu.dto;

import java.util.List;

/**
 * On-demand delivery performance report for one PUDU robot over a date range.
 *
 * <p>Shaped to match {@code AutoxingDeliveryReport} field for field where the meaning
 * is the same — {@code summary}, {@code categories}, {@code daily}, the names and the
 * period label — so the console renders both with one {@code DeliveryReportView}
 * rather than a fork per brand. The units follow that contract too: <strong>metres and
 * seconds</strong>, converted from PUDU's kilometres and hours.
 *
 * <p>Two things AutoXing has that PUDU does not: a live status (PUDU's data-board is
 * statistics only, and the robot's state belongs in the operator UI anyway) and task
 * categories (PUDU's delivery endpoint is delivery only, so {@code categories} carries
 * one entry). Two things PUDU has that AutoXing does not, in {@link Pudu}: tables and
 * trays served, and the previous period of the same length for comparison.
 *
 * <p>On precision: PUDU reports mileage to 0.01 km and duration to 0.01 h. The metre
 * and second figures here are therefore exact multiples of 10 m and 36 s — fine for
 * a period report, misleading if read as a measurement.
 */
public record PuduDeliveryReport(
        String robotId,         // the serial number (PUDU's "sn")
        String robotName,       // PUDU's robot nickname, else "PUDU <model>"
        String model,           // PUDU's product name: "bellabot", "kettybot", "pudubot"
        String customerName,    // supplied override, else the PUDU store name
        String siteBranch,      // the PUDU store name
        String periodLabel,     // e.g. "1–20 July 2026"
        Summary summary,
        List<CategoryStat> categories,
        List<DailyStat> daily,
        Pudu pudu,
        String note) {

    /** Period totals and derived figures. Same fields and units as AutoXing's. */
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

    /** One entry, {@code delivery}, so the view's share ring reads 100% for a busy robot. */
    public record CategoryStat(
            String category,
            int count,
            double mileageMeters,
            long durationSeconds) {
    }

    /** One day of activity, every day of the period present, idle days at zero. */
    public record DailyStat(
            String date,
            int count,
            double mileageMeters,
            long durationSeconds) {
    }

    /** What PUDU reports that the shared shape has no place for. */
    public record Pudu(
            int tableCount,
            int trayCount,
            /** Overall mean, total distance over total running time; null when idle. */
            Double avgSpeedMps,
            /** The period of the same length immediately before, for the same robot. */
            PreviousPeriod previousPeriod) {
    }

    /** Totals for the preceding window of equal length — PUDU's "qoq". */
    public record PreviousPeriod(
            String periodLabel,
            int totalTasks,
            double totalMileageMeters,
            long totalDurationSeconds,
            int tableCount,
            int trayCount) {
    }
}
