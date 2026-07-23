package com.raaspal.robotrecommendation.telemetry.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.AutoxingReportService;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingDeliveryReport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * On-demand AutoXing delivery report preview. Fetches live statistics + robot state
 * directly from the AutoXing API (no persistence) so the team can pull an urgent
 * report for a robot. Authenticated via SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/autoxing/report")
@RequiredArgsConstructor
public class AutoxingReportController {

    private static final int DEFAULT_WINDOW_DAYS = 29; // 30-day inclusive window (from..to)

    private final AutoxingReportService reportService;

    /**
     * Delivery report for one AutoXing robot over {@code [from, to]} (inclusive, max
     * 30 days). Both dates are optional — defaults to the last 30 days ending today.
     */
    @GetMapping("/preview")
    public ApiResponse<AutoxingDeliveryReport> preview(
            @RequestParam String robotId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String robotName,
            @RequestParam(required = false) String model) {
        LocalDate end = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS);
        return ApiResponse.success(reportService.build(robotId, start, end, robotName, model));
    }
}
