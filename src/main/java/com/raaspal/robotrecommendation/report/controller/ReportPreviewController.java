package com.raaspal.robotrecommendation.report.controller;

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

    /** Aggregate one robot's task reports for a month ("YYYY-MM"). */
    @GetMapping
    public ApiResponse<ReportPreviewResponse> preview(
            @RequestParam String serialNumber,
            @RequestParam String month) {
        return ApiResponse.success(reportPreviewService.build(serialNumber, month));
    }
}
