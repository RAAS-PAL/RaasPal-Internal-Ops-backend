package com.raaspal.robotrecommendation.recommendation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record GenerateRecommendationRequest(
        String name,
        @Min(1)
        @Max(3)
        Integer optionCount
) {
}
