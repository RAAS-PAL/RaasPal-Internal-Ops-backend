package com.raaspal.robotrecommendation.report.controller;

import com.raaspal.robotrecommendation.report.service.MonthlyReportService;
import com.raaspal.robotrecommendation.report.service.MonthlyReportSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger for monthly report generation/delivery. Requires a valid JWT
 * (every endpoint is authenticated via SecurityConfig); role-gating to ADMIN is
 * a later hardening once method security is wired.
 *
 * <p>Use {@code testMode=true} first: it generates and uploads each robot's
 * xlsx and returns the signed download URLs <em>without</em> sending anything to
 * n8n/LINE — so the report can be verified internally with zero LINE cost.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final MonthlyReportService monthlyReportService;

    /**
     * @param month    target month {@code "YYYY-MM"}; defaults to the previous month when omitted
     * @param testMode when true (default), generate + upload only — do not send to n8n/LINE
     */
    @PostMapping("/monthly/run")
    public MonthlyReportSummary runMonthly(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "true") boolean testMode) {
        return monthlyReportService.generateAndSend(month, testMode);
    }
}
