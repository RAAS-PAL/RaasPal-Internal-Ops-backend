package com.raaspal.robotrecommendation.partner.dto;

import jakarta.validation.constraints.Size;

/**
 * Admin request to rename a partner and/or enable/disable it. Both fields are
 * optional; a null field is left unchanged. Disabling a partner
 * ({@code active=false}) instantly blocks every one of its API keys.
 */
public record UpdatePartnerRequest(
        @Size(max = 255, message = "Partner name must be at most 255 characters")
        String name,
        Boolean active) {
}
