package com.raaspal.robotrecommendation.ai.dto;

import java.util.List;

public record AiRecommendationResult(
        String aiExplanation,
        List<AiRecommendationOption> options
) {
}
