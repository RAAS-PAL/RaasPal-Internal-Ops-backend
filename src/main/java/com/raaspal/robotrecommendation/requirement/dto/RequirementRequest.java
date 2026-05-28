package com.raaspal.robotrecommendation.requirement.dto;

import com.raaspal.robotrecommendation.common.enums.BudgetBand;
import com.raaspal.robotrecommendation.common.enums.Environment;
import com.raaspal.robotrecommendation.common.enums.InputSource;
import com.raaspal.robotrecommendation.common.enums.RequirementStatus;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RequirementRequest(

        @NotNull
        RobotType robotType,

        @NotBlank
        @Size(max = 255)
        String title,

        String description,

        Environment environment,

        String[] cleaningFunctions,

        String[] floorTypes,

        @Positive
        Integer minPassableWidthMm,

        @Positive
        Integer coverageAreaSqm,

        BudgetBand budgetBand,

        String priorityNotes,

        InputSource inputSource,

        UUID sourceFileId,

        RequirementStatus status
) {
}
