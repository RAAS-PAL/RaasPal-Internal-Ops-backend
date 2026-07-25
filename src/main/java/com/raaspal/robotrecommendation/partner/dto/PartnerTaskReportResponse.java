package com.raaspal.robotrecommendation.partner.dto;

import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single cleaning task report as exposed to a partner. Sourced entirely from
 * the already-synced {@code robot_task_reports} table (no live brand call on
 * read). Carries the cleaning metrics plus the consumable-wear figures
 * (brush / filter / suction-blade residual %) that a service partner uses to
 * plan maintenance, and the robot's serial number for context — but no internal
 * database ids or sync metadata.
 */
public record PartnerTaskReportResponse(
        String robotSerialNumber,
        String externalTaskId,
        String brand,
        String cleaningPlan,
        String mapName,
        String cleaningMode,
        BigDecimal taskCompletionPct,
        Instant startTime,
        Instant endTime,
        Integer workingTimeSeconds,
        BigDecimal plannedAreaSqm,
        BigDecimal cleaningAreaSqm,
        BigDecimal workEfficiencySqmH,
        BigDecimal waterConsumptionL,
        Integer startBatteryPct,
        Integer endBatteryPct,
        BigDecimal brushResidualPct,
        BigDecimal filterResidualPct,
        BigDecimal suctionBladeResidualPct,
        Integer taskEndStatus,
        String operator,
        String reportMonth) {

    public static PartnerTaskReportResponse of(RobotTaskReport r, String serialNumber) {
        return new PartnerTaskReportResponse(
                serialNumber,
                r.getExternalTaskId(),
                r.getBrand(),
                r.getCleaningPlan(),
                r.getMapName(),
                r.getCleaningMode(),
                r.getTaskCompletionPct(),
                r.getStartTime(),
                r.getEndTime(),
                r.getWorkingTimeSeconds(),
                r.getPlannedAreaSqm(),
                r.getCleaningAreaSqm(),
                r.getWorkEfficiencySqmH(),
                r.getWaterConsumptionL(),
                r.getStartBatteryPct(),
                r.getEndBatteryPct(),
                r.getBrushResidualPct(),
                r.getFilterResidualPct(),
                r.getSuctionBladeResidualPct(),
                r.getTaskEndStatus(),
                r.getOperator(),
                r.getReportMonth());
    }
}