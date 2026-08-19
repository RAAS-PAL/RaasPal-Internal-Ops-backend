package com.raaspal.robotrecommendation.customer.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/**
 * Create or update a customer record. Customers are internal report recipients,
 * not login accounts. {@code contactEmail} is where the monthly report is sent;
 * it may hold several addresses separated by commas or semicolons (the report is
 * sent as one email to all of them). Each address is validated in the service.
 *
 * <p>{@code contractStartDate} clips the monthly report so a customer who signed
 * mid-month is not shown work done before their contract began. Null (the default)
 * reports the whole month.
 */
public record CustomerRequest(
        @NotBlank(message = "Company name is required") String companyName,
        String industry,
        String contactEmail,
        String contactPhone,
        String branch,
        String notes,
        LocalDate contractStartDate) {
}
