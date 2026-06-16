package com.raaspal.robotrecommendation.telemetry.core;

import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Brand-agnostic representation of a single robot cleaning task report,
 * produced by a {@link TelemetryAdapter}. Fields not provided by a given
 * brand's API are left null.
 */
@Data
@Builder
public class TelemetryTaskReport {

    private String externalTaskId;
    private String brand;
    private String robotSerialNumber;
    private String operator;

    // ── Common fields ────────────────────────────────────────────────────
    private String cleaningPlan;
    private String mapName;
    private Double taskCompletionPct;
    private Instant startTime;
    private Instant endTime;
    private Double workEfficiencySqmH;
    private Integer workingTimeSeconds;
    private Double cleaningAreaSqm;
    private Double plannedAreaSqm;
    private Integer startBatteryPct;
    private Integer endBatteryPct;
    private Double waterConsumptionL;

    // ── Gausium-specific (nullable for other brands) ───────────────────────
    private Double brushResidualPct;
    private Double filterResidualPct;
    private Double suctionBladeResidualPct;
    private Double plannedPolishingAreaSqm;
    private Double actualPolishingAreaSqm;
    private String cleaningMode;
    private String taskReportPngUri;
    private Integer taskEndStatus;

    /**
     * Maps this brand-agnostic DTO to a persistable {@link RobotTaskReport}.
     * The caller is responsible for setting robotUnit, customerProfile,
     * reportMonth and syncedAt before saving.
     */
    public RobotTaskReport toEntity() {
        return RobotTaskReport.builder()
                .externalTaskId(externalTaskId)
                .brand(brand)
                .operator(operator)
                .cleaningPlan(cleaningPlan)
                .mapName(mapName)
                .taskCompletionPct(toBigDecimal(taskCompletionPct))
                .startTime(startTime)
                .endTime(endTime)
                .workEfficiencySqmH(toBigDecimal(workEfficiencySqmH))
                .workingTimeSeconds(workingTimeSeconds)
                .cleaningAreaSqm(toBigDecimal(cleaningAreaSqm))
                .plannedAreaSqm(toBigDecimal(plannedAreaSqm))
                .startBatteryPct(startBatteryPct)
                .endBatteryPct(endBatteryPct)
                .waterConsumptionL(toBigDecimal(waterConsumptionL))
                .brushResidualPct(toBigDecimal(brushResidualPct))
                .filterResidualPct(toBigDecimal(filterResidualPct))
                .suctionBladeResidualPct(toBigDecimal(suctionBladeResidualPct))
                .plannedPolishingAreaSqm(toBigDecimal(plannedPolishingAreaSqm))
                .actualPolishingAreaSqm(toBigDecimal(actualPolishingAreaSqm))
                .cleaningMode(cleaningMode)
                .taskReportPngUri(taskReportPngUri)
                .taskEndStatus(taskEndStatus)
                .build();
    }

    private static BigDecimal toBigDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}