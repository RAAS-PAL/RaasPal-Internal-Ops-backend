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
        return applyTo(new RobotTaskReport());
    }

    /**
     * Copies this report's brand-sourced values onto an existing row — used by a
     * refresh sync to repair rows stored before a mapping was fixed.
     *
     * <p>Deliberately the single place the field list lives ({@link #toEntity()}
     * delegates here): if inserting and refreshing each had their own copy, a
     * newly mapped field could be added to one and forgotten in the other, and
     * refreshed rows would silently keep stale values.
     *
     * <p>Ownership boundary: the row's identity and linkage — id, robotUnit,
     * customerProfile, reportMonth, syncedAt — belong to the caller and are never
     * touched here.
     */
    public RobotTaskReport applyTo(RobotTaskReport entity) {
        entity.setExternalTaskId(externalTaskId);
        entity.setBrand(brand);
        entity.setOperator(operator);
        entity.setCleaningPlan(cleaningPlan);
        entity.setMapName(mapName);
        entity.setTaskCompletionPct(toBigDecimal(taskCompletionPct));
        entity.setStartTime(startTime);
        entity.setEndTime(endTime);
        entity.setWorkEfficiencySqmH(toBigDecimal(workEfficiencySqmH));
        entity.setWorkingTimeSeconds(workingTimeSeconds);
        entity.setCleaningAreaSqm(toBigDecimal(cleaningAreaSqm));
        entity.setPlannedAreaSqm(toBigDecimal(plannedAreaSqm));
        entity.setStartBatteryPct(startBatteryPct);
        entity.setEndBatteryPct(endBatteryPct);
        entity.setWaterConsumptionL(toBigDecimal(waterConsumptionL));
        entity.setBrushResidualPct(toBigDecimal(brushResidualPct));
        entity.setFilterResidualPct(toBigDecimal(filterResidualPct));
        entity.setSuctionBladeResidualPct(toBigDecimal(suctionBladeResidualPct));
        entity.setPlannedPolishingAreaSqm(toBigDecimal(plannedPolishingAreaSqm));
        entity.setActualPolishingAreaSqm(toBigDecimal(actualPolishingAreaSqm));
        entity.setCleaningMode(cleaningMode);
        entity.setTaskReportPngUri(taskReportPngUri);
        entity.setTaskEndStatus(taskEndStatus);
        return entity;
    }

    private static BigDecimal toBigDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}