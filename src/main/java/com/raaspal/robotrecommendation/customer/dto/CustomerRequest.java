package com.raaspal.robotrecommendation.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Create or update a customer record. Customers are internal report recipients,
 * not login accounts. {@code contactEmail} is where the monthly report is sent.
 */
public record CustomerRequest(
        @NotBlank(message = "Company name is required") String companyName,
        String industry,
        @Email(message = "Contact email must be a valid email address") String contactEmail,
        String contactPhone,
        String branch,
        String notes) {
}
