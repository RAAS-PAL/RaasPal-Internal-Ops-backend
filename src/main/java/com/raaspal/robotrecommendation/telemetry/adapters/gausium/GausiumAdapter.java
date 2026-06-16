package com.raaspal.robotrecommendation.telemetry.adapters.gausium;

import com.raaspal.robotrecommendation.telemetry.adapters.gausium.dto.GausiumConsumablesResidual;
import com.raaspal.robotrecommendation.telemetry.adapters.gausium.dto.GausiumTaskReport;
import com.raaspal.robotrecommendation.telemetry.core.TelemetryAdapter;
import com.raaspal.robotrecommendation.telemetry.core.TelemetryTaskReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@link TelemetryAdapter} for the Gausium brand, backed by
 * {@link GausiumApiClient} and {@link GausiumOAuthService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GausiumAdapter implements TelemetryAdapter {

    private static final String BRAND = "GAUSIUM";

    private final GausiumApiClient apiClient;
    private final GausiumOAuthService oauthService;

    @Override
    public String getBrand() {
        return BRAND;
    }

    @Override
    public boolean supports(String brand) {
        return BRAND.equalsIgnoreCase(brand);
    }

    @Override
    public List<TelemetryTaskReport> fetchTaskReports(String robotSerialNumber, LocalDate from, LocalDate to) {
        if (!apiClient.isConfigured()) {
            throw new GausiumApiException("Gausium API credentials are not configured");
        }
        String accessToken = oauthService.getValidAccessToken();
        List<GausiumTaskReport> reports = apiClient.fetchTaskReports(robotSerialNumber, from, to, accessToken);
        return reports.stream().map(this::toTelemetryTaskReport).toList();
    }

    private TelemetryTaskReport toTelemetryTaskReport(GausiumTaskReport report) {
        GausiumConsumablesResidual residual = report.consumablesResidualPercentage();
        return TelemetryTaskReport.builder()
                .externalTaskId(report.id())
                .brand(BRAND)
                .robotSerialNumber(report.robotSerialNumber())
                .operator(report.operator())
                .cleaningPlan(report.displayName())
                .mapName(report.areaNameList())
                .taskCompletionPct(toPercentage(report.completionPercentage()))
                .startTime(parseInstant(report.startTime()))
                .endTime(parseInstant(report.endTime()))
                .workEfficiencySqmH(report.efficiencySquareMeterPerHour())
                .workingTimeSeconds(report.durationSeconds())
                .cleaningAreaSqm(report.actualCleaningAreaSquareMeter())
                .plannedAreaSqm(report.plannedCleaningAreaSquareMeter())
                .startBatteryPct(report.startBatteryPercentage())
                .endBatteryPct(report.endBatteryPercentage())
                .waterConsumptionL(report.waterConsumptionLiter())
                .brushResidualPct(residual == null || residual.brush() == null ? null : residual.brush().doubleValue())
                .filterResidualPct(residual == null || residual.filter() == null ? null : residual.filter().doubleValue())
                .suctionBladeResidualPct(residual == null || residual.suctionBlade() == null ? null : residual.suctionBlade().doubleValue())
                .plannedPolishingAreaSqm(report.plannedPolishingAreaSquareMeter())
                .actualPolishingAreaSqm(report.actualPolishingAreaSquareMeter())
                .cleaningMode(report.cleaningMode())
                .taskReportPngUri(report.taskReportPngUri())
                .taskEndStatus(report.taskEndStatus())
                .build();
    }

    private static Double toPercentage(Double completionPercentage) {
        return completionPercentage == null ? null : completionPercentage * 100;
    }

    private static Instant parseInstant(String timestamp) {
        return timestamp == null ? null : Instant.parse(timestamp);
    }
}
