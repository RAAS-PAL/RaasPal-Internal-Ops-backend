package com.raaspal.robotrecommendation.recommendation.dto;

import com.raaspal.robotrecommendation.recommendation.entity.RecommendationItem;
import com.raaspal.robotrecommendation.robot.dto.RobotResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record RecommendationItemResponse(
        UUID id,
        UUID recommendationId,
        RobotResponse robot,
        Integer rankPosition,
        BigDecimal totalScore,
        String aiReasoning,
        String fitLevel,
        String proposalTitle,
        String proposalSummary,
        String whyRecommended,
        String customerSummary,
        String matchedRequirements,
        String businessValue,
        String limitations,
        String missingInformation,
        String suggestedNextStep,
        LocalDateTime createdAt
) {
    public static RecommendationItemResponse from(RecommendationItem item) {
        UUID recommendationId = item.getRecommendation() == null ? null : item.getRecommendation().getId();

        return new RecommendationItemResponse(
                item.getId(),
                recommendationId,
                RobotResponse.from(item.getRobot()),
                item.getRankPosition(),
                item.getTotalScore(),
                item.getAiReasoning(),
                item.getFitLevel(),
                item.getProposalTitle(),
                item.getProposalSummary(),
                item.getWhyRecommended(),
                item.getCustomerSummary(),
                item.getMatchedRequirements(),
                item.getBusinessValue(),
                item.getLimitations(),
                item.getMissingInformation(),
                item.getSuggestedNextStep(),
                item.getCreatedAt()
        );
    }
}
