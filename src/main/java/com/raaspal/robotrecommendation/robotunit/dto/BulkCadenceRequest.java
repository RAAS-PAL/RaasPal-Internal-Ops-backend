package com.raaspal.robotrecommendation.robotunit.dto;

import com.raaspal.robotrecommendation.robotunit.entity.ReportCadence;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Bulk report-cadence change. When {@code deploymentIds} is null or empty the
 * cadence is applied to <em>every</em> active deployment; otherwise only to the
 * listed deployments (the UI's checkbox selection).
 */
public record BulkCadenceRequest(
        @NotNull(message = "Report cadence is required") ReportCadence reportCadence,
        List<UUID> deploymentIds) {
}
