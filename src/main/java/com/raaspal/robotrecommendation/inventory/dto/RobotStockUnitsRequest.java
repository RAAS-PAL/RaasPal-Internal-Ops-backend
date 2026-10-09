package com.raaspal.robotrecommendation.inventory.dto;

import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RobotStockUnitsRequest(@NotNull RobotUnitStatus status,
                                     @NotNull @Min(1) Integer quantity) {
}
