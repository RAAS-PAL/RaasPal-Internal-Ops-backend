package com.raaspal.robotrecommendation.robotunit.dto;

import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Move a unit between warehouse states — {@code IN_STOCK} and {@code DEMO}.
 *
 * <p>Deliberately cannot set {@code RENT} or {@code SOLD}. Both describe a
 * commercial arrangement with a customer: RENT is created by deploying the robot,
 * and SOLD records a transfer of ownership. Allowing either from a warehouse screen
 * would let someone mark a robot sold with no deployment, no customer and no record
 * of the sale anywhere — the count would look right while the fleet lied.
 */
public record UpdateStockStatusRequest(

        @NotNull(message = "Status is required")
        RobotUnitStatus status,

        /** Where it now sits. Sensible to clear when sending a unit out on demo. */
        @Size(max = 128)
        String location,

        @Size(max = 255)
        String note
) {
}
