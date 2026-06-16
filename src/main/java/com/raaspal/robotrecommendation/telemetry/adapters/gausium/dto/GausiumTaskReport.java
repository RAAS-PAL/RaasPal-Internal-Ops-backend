package com.raaspal.robotrecommendation.telemetry.adapters.gausium.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single entry in the {@code robotTaskReports} array returned by Gausium's
 * V2 List Robot Task Reports endpoint
 * ({@code GET /openapi/v2alpha1/robots/{robotSerialNumber}/taskReports}).
 * {@code startTime}/{@code endTime} are ISO-8601 UTC instants (e.g.
 * {@code 2024-08-14T06:55:45Z}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GausiumTaskReport(
        String id,
        String displayName,
        String areaNameList,
        String robot,
        String robotSerialNumber,
        String operator,
        Double completionPercentage,
        Integer durationSeconds,
        Double plannedCleaningAreaSquareMeter,
        Double actualCleaningAreaSquareMeter,
        Double efficiencySquareMeterPerHour,
        Double plannedPolishingAreaSquareMeter,
        Double actualPolishingAreaSquareMeter,
        Double waterConsumptionLiter,
        Integer startBatteryPercentage,
        Integer endBatteryPercentage,
        GausiumConsumablesResidual consumablesResidualPercentage,
        String cleaningMode,
        Integer taskEndStatus,
        String taskReportPngUri,
        String startTime,
        String endTime
) {
}
