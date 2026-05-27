package com.raaspal.robotrecommendation.proposal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProposalTemplateRequest(
        @NotBlank
        @Size(max = 255)
        String name,

        String description,

        @NotBlank
        String templateContent,

        @Size(max = 50)
        String contentFormat,

        Boolean active
) {
}
