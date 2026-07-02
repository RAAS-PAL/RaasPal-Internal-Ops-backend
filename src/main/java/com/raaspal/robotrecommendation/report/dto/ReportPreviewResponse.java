package com.raaspal.robotrecommendation.report.dto;

import java.util.List;

/**
 * Aggregated monthly performance report for the web report page. Field names
 * mirror the frontend `MonthlyPerformanceReport` type exactly so it renders
 * with no mapping.
 */
public record ReportPreviewResponse(
        String brand,
        String customerName,
        String siteBranch,
        String robotName,
        String serialNumber,
        String periodLabel,
        Executive executive,
        Operational operational,
        List<Consumable> consumables,
        List<Recommendation> recommendations) {

    /** Part 1 — Executive Summary. */
    public record Executive(
            int totalTasksCompleted,
            String totalOperatingTime,
            double totalAreaCleanedSqm,
            double averageProductivitySqmH,
            double waterConsumptionL,
            String batteryConsumption) {
    }

    /** A Part 2 ring gauge. */
    public record Ring(String label, double percent) {
    }

    /** Part 2 — Operational Performance. */
    public record Operational(
            List<Ring> rings,
            String taskType,
            String taskStatus,
            String tasksPerDay,
            String averageRunTime) {
    }

    /** A Part 3 consumable row (state: "good" | "monitor" | "action"). */
    public record Consumable(String label, double percent, String state) {
    }

    /**
     * A structured recommendation the frontend localizes.
     *
     * <p>{@code type} is one of "action" | "monitor" | "completion" | "healthy".
     * {@code part} is the consumable label ("Brush"/"Filter"/"Squeegee") for
     * action/monitor, else null. {@code value} is the residual % (action/monitor)
     * or average completion % (completion), else null. Kept structured — rather
     * than a pre-built sentence — so the report page can render it in any locale.
     */
    public record Recommendation(String type, String part, Double value) {
    }
}
