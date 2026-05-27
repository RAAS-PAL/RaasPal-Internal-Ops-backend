package com.raaspal.robotrecommendation.ai.dto;

import com.raaspal.robotrecommendation.common.enums.BudgetBand;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.enums.TestStatus;
import com.raaspal.robotrecommendation.robot.dto.RobotSpecResponse;

import java.util.UUID;

public record RobotCatalogData(
        UUID robotId,
        String brand,
        String model,
        RobotType robotType,
        TestStatus testStatus,
        BudgetBand priceBand,
        RobotSpecResponse spec
) {
}
