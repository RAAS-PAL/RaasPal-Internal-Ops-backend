package com.raaspal.robotrecommendation.requirement.dto;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import jakarta.validation.constraints.NotNull;

public record ExtractRequirementRequest(

        @NotNull
        RobotType robotType
) {
}
