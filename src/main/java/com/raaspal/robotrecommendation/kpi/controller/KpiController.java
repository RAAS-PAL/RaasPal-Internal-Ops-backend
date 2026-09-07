package com.raaspal.robotrecommendation.kpi.controller;

import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayApiClient;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayBoardSchema;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.kpi.config.KpiMondayProperties;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse;
import com.raaspal.robotrecommendation.kpi.dto.MondaySyncConfigResponse;
import com.raaspal.robotrecommendation.kpi.dto.MondaySyncRunResponse;
import com.raaspal.robotrecommendation.kpi.entity.CaseTicketSyncRun;
import com.raaspal.robotrecommendation.kpi.repository.CaseTicketSyncRunRepository;
import com.raaspal.robotrecommendation.kpi.service.KpiCaseMetricsService;
import com.raaspal.robotrecommendation.kpi.service.MondayCaseSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The RE KPI dashboard's API. Internal-only: the deck these numbers replace is
 * a board document, so every endpoint here is limited to ADMIN and
 * RAASPAL_TEAM — a CUSTOMER or INVENTORY_STAFF account gets 403.
 *
 * <p>Periods are months, {@code YYYY-MM}, matching the console's period picker;
 * the deck has no finer grain than that.
 */
@RestController
@RequestMapping("/api/v1/kpi")
@PreAuthorize("hasAnyRole('ADMIN', 'RAASPAL_TEAM')")
@RequiredArgsConstructor
public class KpiController {

    private static final int MAX_RUNS = 100;

    private final KpiCaseMetricsService metricsService;
    private final MondayCaseSyncService syncService;
    private final CaseTicketSyncRunRepository runRepository;
    private final MondayBoardReader boardReader;
    private final MondayApiClient apiClient;
    private final KpiMondayProperties properties;

    /**
     * CM-case KPIs per month. Defaults to the six complete months before the
     * current one, in the sync zone — a half-finished month would drag every
     * rate down.
     */
    @GetMapping("/cm-cases")
    public ApiResponse<KpiCaseMetricsResponse> cmCases(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        YearMonth toMonth = to == null ? YearMonth.now(ZoneId.of(properties.getSyncZone())).minusMonths(1) : parseMonth(to, "to");
        YearMonth fromMonth = from == null ? toMonth.minusMonths(5) : parseMonth(from, "from");
        return ApiResponse.success(metricsService.monthly(fromMonth, toMonth));
    }

    /** Starts a full sync of every configured board in the background; poll {@code /monday/sync/status}. */
    @PostMapping("/monday/sync")
    public ResponseEntity<ApiResponse<MondayCaseSyncService.SyncStatus>> startSync() {
        MondayCaseSyncService.SyncStatus status = syncService.start(CaseTicketSyncRun.Trigger.MANUAL);
        return ResponseEntity.accepted().body(ApiResponse.success("monday case sync started", status));
    }

    @GetMapping("/monday/sync/status")
    public ApiResponse<MondayCaseSyncService.SyncStatus> syncStatus() {
        return ApiResponse.success(syncService.status());
    }

    /** Sync history, newest first. */
    @GetMapping("/monday/sync/runs")
    public ApiResponse<List<MondaySyncRunResponse>> syncRuns(@RequestParam(defaultValue = "20") int limit) {
        int size = Math.max(1, Math.min(limit, MAX_RUNS));
        return ApiResponse.success(runRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, size)).stream()
                .map(MondaySyncRunResponse::from)
                .toList());
    }

    /** The effective sync configuration; the token itself is never returned. */
    @GetMapping("/monday/config")
    public ApiResponse<MondaySyncConfigResponse> config() {
        return ApiResponse.success(new MondaySyncConfigResponse(
                apiClient.isConfigured(),
                properties.isSyncEnabled(),
                properties.getSyncCron(),
                properties.getSyncZone(),
                properties.getRepeatWindowDays(),
                properties.getBoards()));
    }

    /**
     * A board's columns and groups, read live, so the column mapping can be
     * confirmed against real ids rather than guessed. Works for any board the
     * token can see, not only the configured ones.
     */
    @GetMapping("/monday/boards/{boardId}")
    public ApiResponse<MondayBoardSchema> describeBoard(@PathVariable String boardId) {
        return ApiResponse.success(boardReader.describeBoard(boardId));
    }

    private static YearMonth parseMonth(String value, String name) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("'" + name + "' must be a month in YYYY-MM form, got '" + value + "'");
        }
    }
}
