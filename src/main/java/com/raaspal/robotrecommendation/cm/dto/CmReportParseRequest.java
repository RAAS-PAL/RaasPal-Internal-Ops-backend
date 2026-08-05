package com.raaspal.robotrecommendation.cm.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The raw Monday.com ticket text pasted by the operator. Parsing this returns a
 * draft for review — it never persists anything.
 */
public record CmReportParseRequest(
        @NotBlank(message = "Ticket text is required") String sourceText) {
}
