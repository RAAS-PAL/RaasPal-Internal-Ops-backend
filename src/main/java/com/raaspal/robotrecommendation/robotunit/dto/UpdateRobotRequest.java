package com.raaspal.robotrecommendation.robotunit.dto;

import com.raaspal.robotrecommendation.robotunit.entity.ReportCadence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Edit an existing robot and its deployment. The serial number is immutable
 * (it is the robot's identity and the key telemetry is synced against), so it
 * is not part of this request. Reassigning {@code customerProfileId} moves the
 * robot's active deployment to another customer.
 */
public record UpdateRobotRequest(
        @NotBlank(message = "Brand is required") String brand,
        String model,
        String name,
        @NotNull(message = "Customer is required") UUID customerProfileId,
        String site,
        ReportCadence reportCadence) {
}
