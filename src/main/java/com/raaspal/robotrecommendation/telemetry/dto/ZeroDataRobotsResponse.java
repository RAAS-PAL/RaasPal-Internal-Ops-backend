package com.raaspal.robotrecommendation.telemetry.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The robots that should have reported a month and logged nothing.
 *
 * @param month        the month asked for, {@code YYYY-MM}
 * @param monthLabel   as the report names it, "July 2026"
 * @param inScope      active deployments whose contract overlaps the month
 * @param zeroData     how many of those logged no task — {@code robots.size()}
 */
public record ZeroDataRobotsResponse(
        String month,
        String monthLabel,
        int inScope,
        int zeroData,
        List<Robot> robots) {

    /**
     * @param lastDataDate      the business-zone date of the last task this robot ever
     *                          logged, or null if it never has — the difference between
     *                          "went quiet" and "never connected"
     * @param daysSinceLastData days from that date to today; null when never
     * @param reason            one of two: never synced, or nothing this month
     */
    public record Robot(
            UUID robotUnitId,
            String serialNumber,
            String name,
            String brand,
            String model,
            UUID customerProfileId,
            String customerName,
            String site,
            LocalDate contractStartDate,
            LocalDate contractEndDate,
            LocalDate lastDataDate,
            Long daysSinceLastData,
            String reason) {
    }
}
