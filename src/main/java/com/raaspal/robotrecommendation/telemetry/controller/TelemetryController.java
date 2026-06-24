package com.raaspal.robotrecommendation.telemetry.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService;
import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService.SyncResult;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * On-demand telemetry sync — pulls a robot's task reports from its brand API
 * (e.g. Gausium) for a date range and stores them. Authenticated via
 * SecurityConfig. The scheduled sync remains the automatic path.
 */
@RestController
@RequestMapping("/api/v1/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetrySyncService telemetrySyncService;

    /** Sync one robot (by serial number) for {@code [from, to]} (robot-local dates). */
    @PostMapping("/sync/{serialNumber}")
    public ApiResponse<SyncResult> sync(
            @PathVariable String serialNumber,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        SyncResult result = telemetrySyncService.syncBySerialNumber(serialNumber, from, to);
        return ApiResponse.success(
                "Synced " + result.saved() + " new task report(s), " + result.skipped() + " already present",
                result);
    }
}
