package com.raaspal.robotrecommendation.report.dto;

import java.util.List;

/**
 * All of a customer's robot reports for one month — what the combined report
 * page and the customer's monthly email link both render.
 */
public record CustomerReportBundleResponse(
        String customerName,
        String periodLabel,
        List<ReportPreviewResponse> robots) {
}
