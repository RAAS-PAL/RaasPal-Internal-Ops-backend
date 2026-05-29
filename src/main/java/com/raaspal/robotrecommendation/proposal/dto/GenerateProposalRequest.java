package com.raaspal.robotrecommendation.proposal.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record GenerateProposalRequest(
        @NotNull
        UUID recommendationItemId,

        UUID proposalTemplateId
) {
}
