package com.raaspal.robotrecommendation.partner.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Admin request to mint a new API key for a partner. The label is a free-text
 * note; {@code expiresInDays} is optional and, when omitted, mints a key that
 * never expires (the behaviour before expiry existed).
 */
public record CreateApiKeyRequest(
        @Size(max = 255, message = "Label must be at most 255 characters")
        String label,

        @Min(value = 1, message = "expiresInDays must be at least 1")
        Integer expiresInDays) {
}
