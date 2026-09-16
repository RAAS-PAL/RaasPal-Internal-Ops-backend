package com.raaspal.robotrecommendation.casereport.scheduler;

import com.raaspal.robotrecommendation.casereport.entity.CaseReportDefinition;
import com.raaspal.robotrecommendation.casereport.service.CaseReportRunService;
import com.raaspal.robotrecommendation.casereport.service.CaseSyncCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The daily record: snapshot both boards, then freeze today's report.
 *
 * <p><strong>Why both, in this order, and why it has to be automatic.</strong> The boards
 * are read live and edited continuously, and monday cannot be asked what a ticket looked
 * like last Tuesday. So a day is only answerable later if something wrote it down while it
 * was happening. Two things do, and they are not interchangeable:
 *
 * <ul>
 *   <li>The <b>snapshot</b> records the boards: each ticket's status that morning and any
 *       comments not yet stored, neither of which monday can give back later.</li>
 *   <li>The <b>frozen run</b> records the report itself, exactly as it read that morning.
 *       This is the one that makes a past date reproducible: without it,
 *       {@code CaseReportRunService} refuses the date rather than fabricating it from
 *       today's board.</li>
 * </ul>
 *
 * <p>Sequential in one method rather than two crons, so the ordering is guaranteed: the
 * snapshot is taken first, and the report is generated against a board that has just been
 * recorded. Two separate schedules could interleave and freeze a report for a day whose
 * snapshot had not been written.
 *
 * <p>⚠️ Off unless {@code app.casereport.sync-enabled} is true, following
 * {@code ReportDeliveryScheduler}. Every day it stays off is a day that cannot be
 * recovered afterwards — which is the argument for turning it on before the rest of the
 * feature is finished rather than after.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.casereport.sync-enabled", havingValue = "true")
public class CaseReportDailyScheduler {

    private final CaseSyncCoordinator syncCoordinator;
    private final CaseReportRunService runService;

    /**
     * Early morning Bangkok, before the team starts editing.
     *
     * <p>A snapshot taken mid-afternoon records a day half-finished, and the report that
     * goes out in the morning is about where things stood, not where they are heading.
     */
    @Scheduled(cron = "${app.casereport.sync-cron:0 15 6 * * *}", zone = "Asia/Bangkok")
    public void recordToday() {
        snapshotBoards();
        freezeTodaysReports();
    }

    private void snapshotBoards() {
        try {
            syncCoordinator.syncAll().forEach(result ->
                    log.info("Daily case sync: board {} — {} seen, {} new, {} closed, "
                                    + "{} status changes",
                            result.boardId(), result.seen(), result.created(),
                            result.closed(), result.statusChanges()));
        } catch (Exception e) {
            // Logged, not rethrown: the report freeze below is still worth attempting, and
            // an exception escaping a scheduled method is retried by nobody and would
            // leave no record of which day was lost.
            log.error("Daily case sync failed. Today's snapshot is missing and cannot be "
                    + "reconstructed later — run POST /api/v1/case-reports/sync by hand.", e);
        }
    }

    /**
     * Freeze today's report for every wired definition.
     *
     * <p>Not a refresh: if somebody already generated it this morning, that run stands.
     * Overwriting it would discard whatever a reviewer had already looked at.
     */
    private void freezeTodaysReports() {
        for (String code : new String[] {
                CaseReportDefinition.MK_PENDING,
                CaseReportDefinition.CLEANING_PENDING,
                CaseReportDefinition.MAKRO_PENDING,
                CaseReportDefinition.AOTGA_PENDING }) {
            try {
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Bangkok"));
                int rows = runService.rowsFor(code, today, false).size();
                log.info("Froze the {} report for {}: {} rows", code, today, rows);
            } catch (Exception e) {
                log.error("Could not freeze the {} report for today. Nobody will be able to "
                        + "reproduce this date later unless it is generated before midnight "
                        + "Bangkok.", code, e);
            }
        }
    }
}
