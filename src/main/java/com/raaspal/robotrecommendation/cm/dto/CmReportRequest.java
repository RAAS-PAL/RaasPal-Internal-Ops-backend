package com.raaspal.robotrecommendation.cm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * A reviewed Corrective Maintenance report, ready to save.
 * <p>
 * Only the customer and the visit date are required. Everything else is
 * deliberately optional: technicians routinely file a ticket before the serial
 * number or test result is known, and refusing to save a partial report would
 * push them back to the Excel template this feature replaces.
 *
 * @param correctiveActions repair steps, one per line, unnumbered
 * @param providerSignature base64 {@code data:} URI, or null to print a blank
 *                          box for a wet signature
 */
public record CmReportRequest(
        @NotNull(message = "Report date is required") LocalDate reportDate,
        String ticketNo,
        @NotBlank(message = "Customer name is required") String customerName,
        String technicianName,
        String robotModel,
        String serialNumber,
        String causeDetail,
        String inspectionResult,
        String correctiveActions,
        String testResult,
        String sourceText,
        String providerSignature,
        String receiverSignature
) {
}
