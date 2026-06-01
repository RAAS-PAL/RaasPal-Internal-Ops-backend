package com.raaspal.robotrecommendation.robot.dto;

import com.raaspal.robotrecommendation.common.enums.BudgetBand;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.enums.TestStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record RobotRequest(
        @NotBlank
        @Size(max = 255)
        String brand,

        @NotBlank
        @Size(max = 255)
        String model,

        @NotNull
        RobotType robotType,

        TestStatus testStatus,

        BudgetBand priceBand,

        @DecimalMin("0.0")
        BigDecimal rentalPrice,

        @DecimalMin("0.0")
        BigDecimal sellingPrice,

        @Size(max = 500)
        String imageUrl,

        @Size(max = 500)
        String datasheetUrl,

        @Valid
        RobotSpecRequest spec
) {
}
