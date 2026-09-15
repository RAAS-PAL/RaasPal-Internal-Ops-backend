package com.raaspal.robotrecommendation.telemetry.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.telemetry.adapters.pudu.PuduApiClient;
import com.raaspal.robotrecommendation.telemetry.adapters.pudu.PuduReportService;
import com.raaspal.robotrecommendation.telemetry.adapters.pudu.dto.PuduDeliveryReport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * On-demand PUDU delivery report preview — the AutoXing preview's counterpart. Reads
 * the data-board live, stores nothing. Authenticated via SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/pudu/report")
@RequiredArgsConstructor
public class PuduReportController {

    private static final int DEFAULT_WINDOW_DAYS = 29; // 30-day inclusive window (from..to)
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    private final PuduReportService reportService;
    private final PuduApiClient apiClient;

    /**
     * Delivery report for one PUDU robot over {@code [from, to]} (inclusive, max 31
     * days). Both dates are optional — defaults to the last 30 days ending today,
     * Bangkok time.
     */
    @GetMapping("/preview")
    public ApiResponse<PuduDeliveryReport> preview(
            @RequestParam String sn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) String customerName) {
        LocalDate end = to != null ? to : LocalDate.now(BUSINESS_ZONE);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS);
        return ApiResponse.success(reportService.build(sn, start, end, shopId, customerName));
    }

    /**
     * Whether credentials are set, and — when asked — whether PUDU accepts them. The
     * console uses the first to decide what to show; the second is the same check as
     * the throwaway script, kept here so nobody has to find that script again.
     */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status(
            @RequestParam(required = false, defaultValue = "false") boolean check) {
        boolean configured = apiClient.isConfigured();
        if (!configured || !check) {
            return ApiResponse.success(Map.of("configured", configured));
        }
        try {
            apiClient.healthCheck();
            return ApiResponse.success(Map.of("configured", true, "healthy", true));
        } catch (RuntimeException e) {
            return ApiResponse.success(Map.of("configured", true, "healthy", false,
                    "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }
}
