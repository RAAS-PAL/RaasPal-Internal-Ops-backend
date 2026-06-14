package com.raaspal.robotrecommendation.report.delivery;

import java.util.List;

/**
 * The JSON body POSTed to the n8n webhook: one payload per customer per month.
 * n8n turns this into a single LINE Flex message (one button per robot) and
 * pushes it to {@code lineUserId}. The backend never calls LINE directly.
 *
 * @param customerId   customer profile id (for n8n logging/idempotency)
 * @param customerName company name shown in the message
 * @param lineUserId   LINE push target (user, group, or room id); may be blank,
 *                     in which case the caller skips sending
 * @param reportMonth  the month covered, {@code "YYYY-MM"}
 * @param robots       one download link per robot deployed to this customer
 */
public record MonthlyReportPayload(
        String customerId,
        String customerName,
        String lineUserId,
        String reportMonth,
        List<RobotReportLink> robots) {

    /**
     * A single robot's report link within a customer's monthly message.
     *
     * @param robotName    human-friendly robot/site name
     * @param serialNumber robot serial number
     * @param downloadUrl  signed Supabase Storage URL for that robot's xlsx
     */
    public record RobotReportLink(String robotName, String serialNumber, String downloadUrl) {
    }
}
