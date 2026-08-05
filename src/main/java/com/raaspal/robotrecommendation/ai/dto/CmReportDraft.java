package com.raaspal.robotrecommendation.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * What the AI pulled out of a pasted Corrective Maintenance ticket.
 * <p>
 * This is a <em>draft</em>, never a saved report: it is returned to the operator
 * for review and correction, and only the edited result is persisted. Every field
 * is nullable because a real ticket often omits several, and the prompt is
 * instructed to return null rather than invent a value.
 *
 * @param correctiveActions repair steps in order, with any "1." / "2." prefix
 *                          already stripped — numbering belongs to the renderer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CmReportDraft(
        String reportDate,
        String ticketNo,
        String customerName,
        String technicianName,
        String robotModel,
        String serialNumber,
        String causeDetail,
        String inspectionResult,
        List<String> correctiveActions,
        String testResult
) {
}
