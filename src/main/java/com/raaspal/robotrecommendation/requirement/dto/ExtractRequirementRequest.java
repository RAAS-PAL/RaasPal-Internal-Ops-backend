package com.raaspal.robotrecommendation.requirement.dto;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ExtractRequirementRequest(
        @NotNull
        UUID customerProfileId,

        @NotNull
        RobotType robotType
) {
}
