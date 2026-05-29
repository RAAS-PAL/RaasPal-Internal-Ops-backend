package com.raaspal.robotrecommendation.robot.dto;

import com.raaspal.robotrecommendation.common.enums.BudgetBand;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.enums.TestStatus;
import com.raaspal.robotrecommendation.robot.entity.Robot;

import java.time.LocalDateTime;
import java.util.UUID;

public record RobotResponse(
        UUID id,
        String brand,
        String model,
        RobotType robotType,
        TestStatus testStatus,
        BudgetBand priceBand,
        String imageUrl,
        String datasheetUrl,
        LocalDateTime createdAt,
        RobotSpecResponse spec
) {
    public static RobotResponse from(Robot robot) {
        return new RobotResponse(
                robot.getId(),
                robot.getBrand(),
                robot.getModel(),
                robot.getRobotType(),
                robot.getTestStatus(),
                robot.getPriceBand(),
                robot.getImageUrl(),
                robot.getDatasheetUrl(),
                robot.getCreatedAt(),
                null
        );
    }

    public static RobotResponse from(Robot robot, RobotSpecResponse spec) {
        return new RobotResponse(
                robot.getId(),
                robot.getBrand(),
                robot.getModel(),
                robot.getRobotType(),
                robot.getTestStatus(),
                robot.getPriceBand(),
                robot.getImageUrl(),
                robot.getDatasheetUrl(),
                robot.getCreatedAt(),
                spec
        );
    }
}
