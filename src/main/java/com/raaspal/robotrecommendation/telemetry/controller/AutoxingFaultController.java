package com.raaspal.robotrecommendation.telemetry.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.AutoxingFaultQueryService;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingFaultSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The recorded AutoXing fault history for one robot: every fault that was active in the
 * period, plus per-code totals. Empty until {@code AUTOXING_FAULT_POLL_ENABLED} has been on.
 */
@RestController
@RequestMapping("/api/v1/autoxing/faults")
@RequiredArgsConstructor
public class AutoxingFaultController {

    private final AutoxingFaultQueryService faults;

    /** Defaults to the last 7 days, Bangkok. */
    @GetMapping
    public ApiResponse<AutoxingFaultSummary> faults(
            @RequestParam String robotId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(ZoneId.of("Asia/Bangkok"));
        LocalDate start = from != null ? from : end.minusDays(6);
        return ApiResponse.success(faults.summarise(robotId.trim(), start, end));
    }
}
