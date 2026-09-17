package com.raaspal.robotrecommendation.kpi.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the CSAT upload history, as the console shows it.
 *
 * @param current true for the workbook a survey is currently computed from —
 *                its most recent upload. Derived, not stored: delete the current
 *                row and the one before it becomes current
 * @param uploadedByName the uploader's name, or null once their account is gone
 */
public record CsatWorkbookHistoryEntry(
        UUID id,
        String stream,
        String streamLabel,
        String fileName,
        long sizeBytes,
        Instant uploadedAt,
        UUID uploadedBy,
        String uploadedByName,
        String note,
        boolean current
) {
}
