package com.raaspal.robotrecommendation.customer.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Create or update a customer record. Customers are internal report recipients,
 * not login accounts. {@code contactEmail} is where the monthly report is sent;
 * it may hold several addresses separated by commas or semicolons (the report is
 * sent as one email to all of them). Each address is validated in the service.
 */
public record CustomerRequest(
        @NotBlank(message = "Company name is required") String companyName,
        String industry,
        String contactEmail,
        String contactPhone,
        String branch,
        String notes) {
}
