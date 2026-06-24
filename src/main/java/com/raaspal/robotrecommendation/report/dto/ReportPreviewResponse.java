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
        List<String> customerQuestions,
        Executive executive,
        Operational operational,
        List<Consumable> consumables,
        List<String> recommendations) {

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
}
