package com.raaspal.robotrecommendation.partner.dto;

import jakarta.validation.constraints.Size;

/** Admin request to mint a new API key for a partner. The label is a free-text note. */
public record CreateApiKeyRequest(
        @Size(max = 255, message = "Label must be at most 255 characters")
        String label) {
}
