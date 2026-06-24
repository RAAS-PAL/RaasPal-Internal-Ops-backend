package com.raaspal.robotrecommendation.robotunit.dto;

import com.raaspal.robotrecommendation.robotunit.entity.ReportCadence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Register a robot by serial number and deploy it to a customer in one step.
 * The robot's report cadence defaults to MONTHLY when omitted.
 */
public record RegisterRobotRequest(
        @NotBlank(message = "Serial number is required") String serialNumber,
        @NotBlank(message = "Brand is required") String brand,
        String model,
        String name,
        @NotNull(message = "Customer is required") UUID customerProfileId,
        String site,
        ReportCadence reportCadence) {
}
