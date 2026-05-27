package com.raaspal.robotrecommendation.proposal.dto;

import com.raaspal.robotrecommendation.proposal.entity.GeneratedProposal;

import java.time.LocalDateTime;
import java.util.UUID;

public record GeneratedProposalResponse(
        UUID id,
        UUID recommendationId,
        UUID recommendationItemId,
        UUID requirementId,
        UUID proposalTemplateId,
        String title,
        String proposalContent,
        String contentFormat,
        String status,
        UUID generatedById,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static GeneratedProposalResponse from(GeneratedProposal proposal) {
        UUID recommendationId = proposal.getRecommendation() == null
                ? null
                : proposal.getRecommendation().getId();
        UUID recommendationItemId = proposal.getRecommendationItem() == null
                ? null
                : proposal.getRecommendationItem().getId();
        UUID requirementId = proposal.getRequirement() == null ? null : proposal.getRequirement().getId();
        UUID proposalTemplateId = proposal.getProposalTemplate() == null
                ? null
                : proposal.getProposalTemplate().getId();
        UUID generatedById = proposal.getGeneratedBy() == null ? null : proposal.getGeneratedBy().getId();

        return new GeneratedProposalResponse(
                proposal.getId(),
                recommendationId,
                recommendationItemId,
                requirementId,
                proposalTemplateId,
                proposal.getTitle(),
                proposal.getProposalContent(),
                proposal.getContentFormat(),
                proposal.getStatus(),
                generatedById,
                proposal.getCreatedAt(),
                proposal.getUpdatedAt()
        );
    }
}
