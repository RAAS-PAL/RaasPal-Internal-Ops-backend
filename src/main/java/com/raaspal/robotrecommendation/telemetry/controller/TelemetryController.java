package com.raaspal.robotrecommendation.telemetry.controller;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService;
import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService.SyncResult;
import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService.SyncStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

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
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean refresh) {
        SyncResult result = telemetrySyncService.syncBySerialNumber(serialNumber, from, to, refresh);
        return ApiResponse.success(
                "Synced " + result.saved() + " new task report(s), " + result.updated()
                        + " updated, " + result.skipped() + " already present",
                result);
    }

    /**
     * Sync actively deployed robots for {@code [from, to]} — the same work the
     * scheduler does, on demand. Useful to backfill history or to verify the
     * pipeline without waiting for the next cron tick. Idempotent: re-running an
     * overlapping range never duplicates rows.
     *
     * <p>Optionally narrowed with {@code partnerId} to just the robots one partner
     * services, which keeps a run proportional to the fleet you care about.
     *
     * <p>With {@code refresh=true}, task reports already stored are re-read from
     * the brand API and <em>updated</em> rather than skipped — how to repair rows
     * synced before a mapping was fixed.
     *
     * <p>Runs inline and can take a while for a large fleet, so callers should use
     * a generous timeout.
     */
    @PostMapping("/sync-all")
    public ApiResponse<SyncStatus> syncAll(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID partnerId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        boolean started = telemetrySyncService.startSyncAsync(from, to, partnerId, refresh);
        if (!started) {
            throw new BadRequestException("A telemetry sync is already running — wait for it to finish");
        }
        return ApiResponse.success("Telemetry sync started", telemetrySyncService.status());
    }

    /**
     * Progress of the running sync, or the outcome of the last finished one.
     * Polled by the UI while a fleet sync runs.
     */
    @GetMapping("/sync-status")
    public ApiResponse<SyncStatus> syncStatus() {
        return ApiResponse.success(telemetrySyncService.status());
    }
}
