package com.raaspal.robotrecommendation.inventory.dto;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What the inventory team enters for a robot they hold. */
public record RobotStockEntryRequest(

        /** CLEANING when omitted — every robot in the fleet today. */
        RobotType robotType,

        @NotBlank(message = "Brand is required")
        @Size(max = 100)
        String brand,

        @NotBlank(message = "Model is required")
        @Size(max = 255)
        String model,

        /** Revision or configuration — "v1.3", "Roller Brush". */
        @Size(max = 100)
        String version,

        /**
         * http(s) URL or a base64 {@code data:} URI.
         *
         * <p>Omit to leave an existing photo alone; send an empty string to remove
         * it. A form without a picker must not silently wipe one.
         */
        String imageUrl,

        @Min(value = 0, message = "Quantity cannot be negative")
        Integer quantity,

        /** IN_STOCK or DEMO. Anything else is rejected. */
        RobotUnitStatus status,

        @Size(max = 128)
        String location,

        @Size(max = 2000)
        String note
) {
}
