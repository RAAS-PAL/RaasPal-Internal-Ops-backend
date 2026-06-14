package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.report.delivery.MonthlyReportPayload;

import java.util.List;

/**
 * Result of a monthly report run, returned by the manual endpoint and logged
 * by the scheduler.
 *
 * @param reportMonth        the month processed, {@code "YYYY-MM"}
 * @param testMode           whether this was a dry run (files generated + uploaded, nothing sent to n8n)
 * @param customersProcessed customers that produced at least one report file
 * @param robotsReported     report files generated + uploaded across all customers
 * @param messagesSent       payloads successfully POSTed to n8n (always 0 in test mode)
 * @param recipientsSkipped  customers skipped because they have no {@code lineUserId}
 * @param robotErrors        robots whose file generation/upload failed (logged, did not abort the run)
 * @param sendErrors         customers whose n8n POST failed (logged, did not abort the run)
 * @param previews           in test mode, the payloads that would have been sent (incl. signed URLs); empty otherwise
 */
public record MonthlyReportSummary(
        String reportMonth,
        boolean testMode,
        int customersProcessed,
        int robotsReported,
        int messagesSent,
        int recipientsSkipped,
        int robotErrors,
        int sendErrors,
        List<MonthlyReportPayload> previews) {
}
