package com.raaspal.robotrecommendation.report.dto;

import java.util.List;
import java.util.UUID;

/**
 * The staff-facing view of a customer's combined monthly report: every deployed
 * robot, whether it did anything that month, and whether it is currently held
 * back from the bundle.
 * <p>
 * Deliberately a separate type from {@link CustomerReportBundleResponse}, which
 * is what the customer's public link renders. That one carries only the reports
 * actually being sent; robot ids, activity flags and exclusion state are internal
 * curation details and have no business crossing to the customer-facing page.
 *
 * @param includedCount how many robots the customer would currently see, so the
 *                      UI can warn before sending an empty bundle
 */
public record CustomerBundlePreviewResponse(
        UUID customerProfileId,
        String customerName,
        String periodLabel,
        String month,
        int includedCount,
        List<Robot> robots) {

    /**
     * @param hasData  false when the robot logged no tasks at all that month —
     *                 usually offline. Its report renders as a wall of zeros, so
     *                 this is the flag the UI uses to suggest excluding it.
     * @param excluded currently held back from the bundle
     */
    public record Robot(
            UUID robotUnitId,
            String serialNumber,
            String robotName,
            String site,
            boolean hasData,
            boolean excluded,
            ReportPreviewResponse report) {
    }
}
