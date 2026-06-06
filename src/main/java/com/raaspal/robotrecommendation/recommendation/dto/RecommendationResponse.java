package com.raaspal.robotrecommendation.recommendation.dto;

import com.raaspal.robotrecommendation.common.enums.RecommendationStatus;
import com.raaspal.robotrecommendation.recommendation.entity.Recommendation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record RecommendationResponse(
        UUID id,
        String name,
        UUID requirementId,
        RecommendationStatus status,
        String aiExplanation,
        UUID createdById,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<RecommendationItemResponse> options
) {
    public static RecommendationResponse from(Recommendation recommendation) {
        return from(recommendation, List.of());
    }

    public static RecommendationResponse from(
            Recommendation recommendation,
            List<RecommendationItemResponse> options
    ) {
        UUID requirementId = recommendation.getRequirement() == null
                ? null
                : recommendation.getRequirement().getId();
        UUID createdById = recommendation.getCreatedBy() == null
                ? null
                : recommendation.getCreatedBy().getId();

        return new RecommendationResponse(
                recommendation.getId(),
                recommendation.getName(),
                requirementId,
                recommendation.getStatus(),
                recommendation.getAiExplanation(),
                createdById,
                recommendation.getCreatedAt(),
                recommendation.getUpdatedAt(),
                options
        );
    }
}
