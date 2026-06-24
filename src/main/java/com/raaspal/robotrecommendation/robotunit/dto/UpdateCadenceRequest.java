package com.raaspal.robotrecommendation.robotunit.dto;

import com.raaspal.robotrecommendation.robotunit.entity.ReportCadence;
import jakarta.validation.constraints.NotNull;

/** Change a deployment's report cadence (Monthly / Weekly / Off). */
public record UpdateCadenceRequest(
        @NotNull(message = "Report cadence is required") ReportCadence reportCadence) {
}
