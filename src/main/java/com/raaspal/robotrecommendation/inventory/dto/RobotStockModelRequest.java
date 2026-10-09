package com.raaspal.robotrecommendation.inventory.dto;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Model metadata only: a rename must never change a shelf count or its undo value. */
public record RobotStockModelRequest(@NotNull RobotType robotType,
                                     @NotBlank @Size(max = 100) String brand,
                                     @NotBlank @Size(max = 255) String model,
                                     @Size(max = 100) String version,
                                     String imageUrl) {
}
