package com.raaspal.robotrecommendation.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * A plain announcement email sent to selected customers. The email body is
 * EXACTLY {@code message} — no report links, no template, nothing else is added.
 * {@code cc} (optional) addresses are CC'd on every recipient's email.
 */
public record AnnouncementRequest(
        @NotEmpty(message = "Select at least one customer") List<UUID> customerProfileIds,
        @NotBlank(message = "Subject is required") String subject,
        @NotBlank(message = "Message is required") String message,
        List<String> cc) {
}
