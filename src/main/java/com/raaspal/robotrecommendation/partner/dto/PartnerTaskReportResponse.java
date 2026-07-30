package com.raaspal.robotrecommendation.partner.dto;

import com.raaspal.robotrecommendation.telemetry.core.CleaningModeLabels;
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
        /**
         * English label, translated from the manufacturer's own code using Gausium's
         * own wording.
         *
         * <p>The untranslated value is deliberately <strong>not</strong> exposed. It
         * was, alongside this field, so a task could be reconciled against Gausium's
         * portal — but PCS asked not to receive the Chinese at all, and shipping both
         * invites a partner to build against the raw form and inherit every firmware
         * inconsistency this mapping exists to absorb. The raw value is still stored
         * unchanged, so RAASPAL staff can reconcile internally.
         */
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
                // Translated on the way out, never at storage: the stored value stays
                // faithful to the manufacturer, so fixing or extending the mapping
                // corrects every response without re-syncing historical rows.
                CleaningModeLabels.toEnglish(r.getCleaningMode()),
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