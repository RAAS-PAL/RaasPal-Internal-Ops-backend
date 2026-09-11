package com.raaspal.robotrecommendation.pm.controller;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.pm.dto.*;
import com.raaspal.robotrecommendation.pm.entity.PmSyncRun;
import com.raaspal.robotrecommendation.pm.service.MondayPmSyncService;
import com.raaspal.robotrecommendation.pm.service.PmPlanningService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * The PM 52-week planner.
 *
 * <p>Read-only over the mirrored monday data. Nothing here writes to monday or to
 * a planning layer: monday remains the single place PM is scheduled, and this is
 * the view of it that monday cannot give.
 */
@RestController
@RequestMapping("/api/v1/pm")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'RAASPAL_TEAM')")
public class PmPlanningController {

    private final PmPlanningService planningService;
    private final MondayPmSyncService syncService;

    /** The 52-week grid for one ISO year. */
    @GetMapping("/year")
    public ResponseEntity<ApiResponse<PmYearResponse>> year(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String serviceLine,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) String province,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String q,
            @RequestParam(name = "excludeCompany", required = false) List<String> excludeCompany) {

        int resolvedYear = year != null ? year : LocalDate.now().getYear();
        if (resolvedYear < 2000 || resolvedYear > 2100) {
            throw new BadRequestException("year must be between 2000 and 2100");
        }
        PmFilter filter = PmFilter.of(serviceLine, region, zone, province, status, owner, q, excludeCompany);
        return ResponseEntity.ok(ApiResponse.success(planningService.year(resolvedYear, filter)));
    }

    /**
     * Visits in a month, or in an explicit date range.
     *
     * <p>The range form is what the look-ahead chips use: "next 90 days" is not a
     * month, and expressing it as three month requests would split trips that
     * straddle a month end.
     */
    @GetMapping("/month")
    public ResponseEntity<ApiResponse<PmMonthResponse>> month(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "false") boolean includeUndated,
            @RequestParam(required = false) String serviceLine,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) String province,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String q,
            @RequestParam(name = "excludeCompany", required = false) List<String> excludeCompany) {

        LocalDate rangeFrom;
        LocalDate rangeTo;
        if (from != null || to != null) {
            if (from == null || to == null) {
                throw new BadRequestException("from and to must be supplied together");
            }
            if (to.isBefore(from)) {
                throw new BadRequestException("to must not be before from");
            }
            rangeFrom = from;
            rangeTo = to;
        } else {
            YearMonth target = parseMonth(month);
            rangeFrom = target.atDay(1);
            rangeTo = target.atEndOfMonth();
        }

        PmFilter filter = PmFilter.of(serviceLine, region, zone, province, status, owner, q, excludeCompany);
        return ResponseEntity.ok(ApiResponse.success(
                planningService.range(rangeFrom, rangeTo, includeUndated, filter)));
    }

    /** Options for the filter bar. */
    @GetMapping("/filters")
    public ResponseEntity<ApiResponse<PmFilterOptions>> filters() {
        return ResponseEntity.ok(ApiResponse.success(planningService.filterOptions()));
    }

    /** Pulls both monday PM boards now. Runs in the request thread; a full sync takes seconds. */
    @PostMapping("/monday/sync")
    public ResponseEntity<ApiResponse<MondayPmSyncService.SyncSummary>> sync(Authentication authentication) {
        String triggeredBy = authentication != null ? authentication.getName() : "unknown";
        MondayPmSyncService.SyncSummary summary = syncService.syncAll(triggeredBy);
        return ResponseEntity.ok(ApiResponse.success(
                summary.failures() == 0 ? "PM sync complete" : "PM sync finished with errors", summary));
    }

    /** Whether a sync is running, and how the last few went. */
    @GetMapping("/monday/sync/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncStatus() {
        List<PmSyncRun> runs = syncService.recentRuns(10);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "running", syncService.isRunning(),
                "runs", runs)));
    }

    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("month must be formatted as YYYY-MM, for example 2026-09");
        }
    }
}
