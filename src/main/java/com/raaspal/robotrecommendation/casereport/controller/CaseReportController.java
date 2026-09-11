package com.raaspal.robotrecommendation.casereport.controller;

import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.entity.CaseReportDefinition;
import com.raaspal.robotrecommendation.casereport.entity.CaseReportRun;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketRepository;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketStatusHistoryRepository;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketUpdateRepository;
import com.raaspal.robotrecommendation.casereport.service.CaseReportRunService;
import com.raaspal.robotrecommendation.casereport.service.CaseSyncCoordinator;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Pending-case reports: generate one, look at a stored one, and run the sync.
 *
 * <p>Nothing here sends anything. The team delivers the file by hand today and will keep
 * doing so until the generated report has been checked against the manual one enough
 * times to be trusted.
 */
@RestController
@RequestMapping("/api/v1/case-reports")
@RequiredArgsConstructor
public class CaseReportController {

    /**
     * The business day a report defaults to.
     *
     * <p>Bangkok, not the server's zone: a report generated at 07:00 Bangkok is this
     * morning's, and on a UTC clock that is still yesterday — so every day count would be
     * one short.
     */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    private final CaseReportRunService runService;
    private final CaseSyncCoordinator syncCoordinator;

    // Read directly for the diagnostic below. Counting rows does not warrant a service.
    private final CaseTicketRepository tickets;
    private final CaseTicketUpdateRepository comments;
    private final CaseTicketStatusHistoryRepository statusHistory;

    /**
     * The MK sheet — MK, Yayoi and Bonus Suki delivery cases.
     *
     * <p>Frozen on first generation. Asking again for the same date returns what was
     * stored rather than re-reading monday, because the board moves under you: a case that
     * has since closed would vanish from yesterday's report and one whose status changed
     * would report today's value under yesterday's heading.
     *
     * @param asOf    the business date; defaults to today in Bangkok
     * @param refresh regenerate a stored draft from the board. Refused once the run has
     *                been sent — that version is the record of what the customer received.
     */
    @GetMapping("/mk")
    public ApiResponse<List<CaseReportRow>> mk(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "false") boolean refresh) {

        LocalDate date = asOf != null ? asOf : LocalDate.now(BUSINESS_ZONE);
        return ApiResponse.success(
                runService.rowsFor(CaseReportDefinition.MK_PENDING, date, refresh));
    }

    /**
     * Whether a date is already frozen, and what state it is in.
     *
     * <p>So the screen can say "generated at 08:12, not yet sent" rather than leaving a
     * reviewer to guess whether they are looking at live data or a stored copy.
     */
    @GetMapping("/mk/run")
    public ApiResponse<Map<String, Object>> mkRun(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        LocalDate date = asOf != null ? asOf : LocalDate.now(BUSINESS_ZONE);
        CaseReportRun run = runService.findRun(CaseReportDefinition.MK_PENDING, date);

        if (run == null) {
            return ApiResponse.success(Map.of("runDate", date.toString(), "exists", false));
        }

        return ApiResponse.success(Map.of(
                "runDate", date.toString(),
                "exists", true,
                "status", run.getStatus().name(),
                "ticketCount", run.getTicketCount(),
                "generatedAt", String.valueOf(run.getGeneratedAt()),
                "replaceable", run.getStatus().isReplaceable()));
    }

    /**
     * Throw away a stored run.
     *
     * <p>For a generation that should not stand. A report produced for the wrong date is
     * the case this exists for: it looks authoritative and there is otherwise no way to
     * remove it.
     *
     * <p>Refused once the run has been sent.
     */
    @DeleteMapping("/mk/run")
    public ApiResponse<Void> discardMkRun(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        runService.discardRun(CaseReportDefinition.MK_PENDING, asOf);
        return ApiResponse.success("Discarded the run for " + asOf);
    }
    /**
     * Record today's state of both boards.
     *
     * <p>POST because it writes. Manual for now so it can be run and inspected; the
     * scheduler that calls it every morning is off by default.
     *
     * <p>Safe to run repeatedly on the same day: a status-history row is written only when
     * the status has actually moved, and today's row is updated rather than duplicated.
     */
    @PostMapping("/sync")
    public ApiResponse<List<CaseTicketSyncService.SyncResult>> sync() {
        return ApiResponse.success("Boards synced", syncCoordinator.syncAll());
    }

    /**
     * What the snapshot actually holds.
     *
     * <p>Worth an endpoint rather than a database query: the sync reports how many rows it
     * changed, and "0 status changes" is ambiguous — it means either nothing moved or
     * nothing was written. This says which, and it is how anyone confirms the record is
     * accumulating rather than quietly doing nothing every morning.
     */
    @GetMapping("/sync/status")
    public ApiResponse<Map<String, Object>> syncStatus() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        return ApiResponse.success(Map.of(
                "today", today.toString(),
                "openTicketsDelivery", tickets.countBySourceBoardIdAndIsPresentTrue("1647612496"),
                "openTicketsCleaning", tickets.countBySourceBoardIdAndIsPresentTrue("3451717331"),
                "ticketsTotal", tickets.count(),
                "commentsStored", comments.count(),
                "statusHistoryRows", statusHistory.count(),
                "statusHistoryToday", statusHistory.countByObservedOn(today)));
    }
}
