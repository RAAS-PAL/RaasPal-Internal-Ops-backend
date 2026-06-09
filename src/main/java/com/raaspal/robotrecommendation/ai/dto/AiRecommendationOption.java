package com.raaspal.robotrecommendation.ai.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record AiRecommendationOption(
        @NotNull
        UUID robotId,

        Integer rankPosition,

        String fitLevel,

        String proposalTitle,

        String proposalSummary,

        String whyRecommended,

        List<String> customerSummary,

        String matchedRequirements,

        String businessValue,

        String limitations,

        String missingInformation,

        String suggestedNextStep
) {
}
