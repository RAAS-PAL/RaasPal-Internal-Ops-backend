package com.raaspal.robotrecommendation.telemetry.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService;
import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService.SyncResult;
import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService.SyncSummary;
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

    /**
     * Sync <em>every</em> actively deployed robot for {@code [from, to]} — the same
     * work the scheduler does, on demand. Useful to backfill history or to verify
     * the pipeline without waiting for the next cron tick. Idempotent: re-running
     * an overlapping range never duplicates rows.
     *
     * <p>Runs inline and can take a while for a large fleet, so callers should use
     * a generous timeout.
     */
    @PostMapping("/sync-all")
    public ApiResponse<SyncSummary> syncAll(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        SyncSummary summary = telemetrySyncService.syncAllActive(from, to);
        return ApiResponse.success(
                "Synced " + summary.robotsSynced() + " robot(s): " + summary.saved() + " new report(s), "
                        + summary.duplicatesSkipped() + " duplicate(s), " + summary.robotsSkipped() + " skipped, "
                        + summary.robotsFailed() + " failed",
                summary);
    }
}
