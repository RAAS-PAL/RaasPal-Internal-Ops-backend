package com.raaspal.robotrecommendation.report.controller;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse;
import com.raaspal.robotrecommendation.report.service.ReportPreviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only aggregated report for the web report page. Authenticated via
 * SecurityConfig. Returns the same shape the report UI renders.
 */
@RestController
@RequestMapping("/api/v1/reports/preview")
@RequiredArgsConstructor
public class ReportPreviewController {

    private final ReportPreviewService reportPreviewService;

    /**
     * Aggregates one robot's task reports for a period: either a calendar
     * {@code month} ("YYYY-MM") or an ISO {@code week} ("YYYY-Www", Mon–Sun).
     * Exactly one of the two is required — both together is a 400 rather than a
     * silent winner, so a caller never gets a period it did not ask for.
     */
    @GetMapping
    public ApiResponse<ReportPreviewResponse> preview(
            @RequestParam String serialNumber,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String week) {

        boolean hasMonth = month != null && !month.isBlank();
        boolean hasWeek = week != null && !week.isBlank();
        if (hasMonth == hasWeek) {
            throw new BadRequestException("Provide exactly one of 'month' (YYYY-MM) or 'week' (YYYY-Www)");
        }

        return ApiResponse.success(hasWeek
                ? reportPreviewService.buildForWeek(serialNumber, week)
                : reportPreviewService.build(serialNumber, month));
    }
}
