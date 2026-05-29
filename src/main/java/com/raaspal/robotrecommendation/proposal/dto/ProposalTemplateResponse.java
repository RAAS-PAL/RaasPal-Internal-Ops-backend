package com.raaspal.robotrecommendation.proposal.dto;

import com.raaspal.robotrecommendation.proposal.entity.ProposalTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProposalTemplateResponse(
        UUID id,
        String name,
        String description,
        String templateContent,
        String contentFormat,
        boolean active,
        UUID createdById,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ProposalTemplateResponse from(ProposalTemplate template) {
        UUID createdById = template.getCreatedBy() == null ? null : template.getCreatedBy().getId();

        return new ProposalTemplateResponse(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.getTemplateContent(),
                template.getContentFormat(),
                template.isActive(),
                createdById,
                template.getCreatedAt(),
                template.getUpdatedAt()
        );
    }
}
