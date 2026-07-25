package com.raaspal.robotrecommendation.partner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Admin request to register a new distributor / service partner. */
public record CreatePartnerRequest(
        @NotBlank(message = "Partner name is required")
        @Size(max = 255, message = "Partner name must be at most 255 characters")
        String name) {
}
