package com.raaspal.robotrecommendation.kpi.controller;

import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayApiClient;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayBoardRef;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayBoardSchema;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.kpi.config.KpiMondayProperties;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse;
import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.kpi.dto.CsatWorkbookHistoryEntry;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse;
import com.raaspal.robotrecommendation.kpi.dto.MondaySyncConfigResponse;
import com.raaspal.robotrecommendation.kpi.dto.MondaySyncRunResponse;
import com.raaspal.robotrecommendation.kpi.entity.CaseTicketSyncRun;
import com.raaspal.robotrecommendation.kpi.export.KpiCaseXlsxExporter;
import com.raaspal.robotrecommendation.kpi.export.KpiCsatXlsxExporter;
import com.raaspal.robotrecommendation.kpi.repository.CaseTicketSyncRunRepository;
import com.raaspal.robotrecommendation.kpi.service.KpiCaseMetricsService;
import com.raaspal.robotrecommendation.kpi.service.CsatWorkbookUploadService;
import com.raaspal.robotrecommendation.kpi.service.KpiCsatService;
import com.raaspal.robotrecommendation.kpi.service.MondayCaseSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

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
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final KpiCaseMetricsService metricsService;
    private final KpiCsatService csatService;
    private final CsatWorkbookUploadService csatUploads;
    private final KpiCaseXlsxExporter caseExporter;
    private final KpiCsatXlsxExporter csatExporter;
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
        Period period = period(from, to);
        return ApiResponse.success(metricsService.monthly(period.from(), period.to()));
    }

    /**
     * The same KPIs as a spreadsheet, one sheet per panel of the deck.
     *
     * <p>The console's charts are HTML, so they paste into a slide only as a
     * picture — no use to anyone who then has to correct a figure or recolour a
     * series. This hands over the numbers instead, shaped so Insert Chart in
     * Excel reproduces the panel and the result stays editable on the slide.
     */
    @GetMapping("/cm-cases/export")
    public ResponseEntity<byte[]> exportCmCases(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) throws IOException {
        Period period = period(from, to);
        return xlsx("re-kpi-report", period,
                caseExporter.export(metricsService.monthly(period.from(), period.to())));
    }

    /**
     * CSAT per month, from the RE team's survey workbooks. Not live: the
     * figures move when the team replaces the workbooks, roughly monthly, and
     * {@code asOf} says how far they run. Same default period as the CM cases.
     */
    @GetMapping("/csat")
    public ApiResponse<KpiCsatResponse> csat(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Period period = period(from, to);
        return ApiResponse.success(csatService.monthly(period.from(), period.to()));
    }

    /** CSAT as a spreadsheet: Top Box by month and survey, and the counts behind it. */
    @GetMapping("/csat/export")
    public ResponseEntity<byte[]> exportCsat(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) throws IOException {
        Period period = period(from, to);
        return xlsx("re-kpi-csat", period, csatExporter.export(csatService.monthly(period.from(), period.to())));
    }

    /** What the workbook source holds right now: files, surveys, how far they run. */
    @GetMapping("/csat/source")
    public ApiResponse<KpiCsatService.SourceStatus> csatSource() {
        return ApiResponse.success(csatService.status());
    }

    /**
     * Re-reads the workbooks now. Normally unnecessary — a replaced file is
     * noticed on the next request — but it is the honest answer to "I just
     * uploaded them, why hasn't it changed?".
     */
    @PostMapping("/csat/reload")
    public ApiResponse<KpiCsatService.SourceStatus> reloadCsat() {
        return ApiResponse.success("csat workbooks reloaded", csatService.reload());
    }

    /**
     * Uploads one survey workbook, making it the current one for its survey.
     *
     * <p>Parsed before it is stored, so a file whose survey cannot be told, or
     * that holds no readable month sheet, is refused here rather than accepted
     * and then silently ignored by every later read. Re-uploading the file that
     * is already current is answered {@code duplicate} instead of adding a row
     * indistinguishable from the one above it.
     */
    @PostMapping(value = "/csat/workbooks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CsatWorkbookUploadService.UploadResult> uploadCsatWorkbook(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String note,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        CsatWorkbookUploadService.UploadResult result =
                csatUploads.upload(file, principal == null ? null : principal.getId(), note);
        return ApiResponse.success(result.duplicate()
                ? "That file is already the current " + result.stream() + " workbook"
                : "Uploaded " + result.fileName(), result);
    }

    /** Every upload, newest first, with the current one per survey marked. */
    @GetMapping("/csat/workbooks")
    public ApiResponse<List<CsatWorkbookHistoryEntry>> csatWorkbooks() {
        return ApiResponse.success(csatUploads.history());
    }

    /** The stored file itself — what was uploaded, byte for byte. */
    @GetMapping("/csat/workbooks/{id}/download")
    public ResponseEntity<byte[]> downloadCsatWorkbook(@PathVariable UUID id) {
        CsatWorkbookUploadService.Download download = csatUploads.download(id);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.fileName()).build().toString())
                .body(download.content());
    }

    /**
     * Removes one upload. Deleting the current workbook for a survey is how a
     * wrong upload is undone: the one before it becomes current again and CSAT
     * goes back to what it said before.
     */
    @DeleteMapping("/csat/workbooks/{id}")
    public ApiResponse<CsatWorkbookHistoryEntry> deleteCsatWorkbook(@PathVariable UUID id) {
        return ApiResponse.success("Deleted", csatUploads.delete(id));
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
     * Every board the token can see — id and name only, no rows — so a board id
     * can be found without reading it out of a monday URL.
     */
    @GetMapping("/monday/boards")
    public ApiResponse<List<MondayBoardRef>> listBoards(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(boardReader.listBoards(Math.max(1, Math.min(limit, 200))));
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

    /**
     * The month range a request asks for, defaulted the same way for every
     * endpoint: the six complete months before the current one, in the sync
     * zone. A half-finished month would drag every rate down.
     */
    private record Period(YearMonth from, YearMonth to) {
    }

    private Period period(String from, String to) {
        YearMonth toMonth = to == null
                ? YearMonth.now(ZoneId.of(properties.getSyncZone())).minusMonths(1)
                : parseMonth(to, "to");
        YearMonth fromMonth = from == null ? toMonth.minusMonths(5) : parseMonth(from, "from");
        return new Period(fromMonth, toMonth);
    }

    /** A download named for what it holds, so a folder of them stays legible. */
    private static ResponseEntity<byte[]> xlsx(String prefix, Period period, byte[] body) {
        String filename = prefix + "_" + period.from() + "_" + period.to() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(XLSX)
                .body(body);
    }

    private static YearMonth parseMonth(String value, String name) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("'" + name + "' must be a month in YYYY-MM form, got '" + value + "'");
        }
    }
}
