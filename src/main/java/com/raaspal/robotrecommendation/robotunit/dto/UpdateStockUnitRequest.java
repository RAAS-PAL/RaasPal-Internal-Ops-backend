package com.raaspal.robotrecommendation.robotunit.dto;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Edit a robot that is in the warehouse.
 *
 * <p>Separate from {@link UpdateRobotRequest}, which requires a
 * {@code customerProfileId} because it edits a <em>deployed</em> robot and its
 * deployment. A unit in stock has no customer, so that request cannot express it.
 *
 * <p>The serial number is absent and immutable: it is the robot's identity, the key
 * telemetry syncs against, and the handle the partner API exposes. Correcting a
 * mistyped serial means deleting the unit and receiving it again.
 */
public record UpdateStockUnitRequest(

        @NotBlank(message = "Brand is required")
        String brand,

        String model,

        String name,

        /** CLEANING when omitted. */
        RobotType robotType,

        /** Catalogue model, and therefore the route to specs. Null unlinks it. */
        UUID robotId,

        @Size(max = 128)
        String location,

        /** IN_STOCK or DEMO only — RENT and SOLD follow from a customer agreement. */
        RobotUnitStatus status
) {
}
