package com.raaspal.robotrecommendation.casereport.scheduler;

import com.raaspal.robotrecommendation.casereport.aotsheet.AotSheetAutoSync;
import com.raaspal.robotrecommendation.casereport.entity.CaseReportDefinition;
import com.raaspal.robotrecommendation.casereport.service.CaseReportRunService;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps today's pending-case sheets close to the board through the day.
 *
 * <p>The daily freeze ({@link CaseReportDailyScheduler}) writes each sheet at 06:15, and
 * until 2026-09-28 that copy stood until someone pressed Regenerate. The boards change
 * all day, and the team wanted the page already current when they open it rather than
 * a minutes-long regeneration on demand. So every 15 minutes this regenerates today's
 * six sheets — the same Regenerate a reviewer presses: rows someone edited, added or
 * removed are kept (merged under a row lock against the run as it stands at that
 * moment), and a sheet already sent is refused, not overwritten.
 *
 * <p><b>The day's refreshing ends with the day.</b> A cycle regenerates the date it
 * started on; after midnight Bangkok that date is in the past, which the run service
 * refuses to regenerate (the board no longer describes it). The last refresh of a day
 * is therefore the 23:45 cycle, and that is the copy the date keeps.
 *
 * <p><b>Cost.</b> Each cycle reads the two boards; the model is asked again only for a
 * ticket whose comments or status changed since it was last summarised (see the caches
 * in {@code SolutionLineWriter} and {@code PartsLineWriter}).
 *
 * <p><b>AOT's Google Sheet</b> is synced at the end of each cycle too, when its sync is on.
 *
 * <p><b>Its own thread.</b> Spring runs every {@code @Scheduled} method on one shared
 * thread, which also runs the CVTE poll every 30 seconds and Monday's weekly report
 * delivery. A cycle takes minutes, so it is handed to a single worker here and the
 * scheduling thread returns at once; a cycle still running when the next is due is
 * skipped rather than queued.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.casereport.refresh-enabled", havingValue = "true")
public class CaseReportRefreshScheduler {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final CaseReportRunService runService;
    private final AotSheetAutoSync aotSheet;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "case-report-refresh");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);

    public CaseReportRefreshScheduler(CaseReportRunService runService, AotSheetAutoSync aotSheet) {
        this.runService = runService;
        this.aotSheet = aotSheet;
    }

    @Scheduled(cron = "${app.casereport.refresh-cron:0 */15 7-23 * * *}", zone = "Asia/Bangkok")
    public void refreshToday() {
        if (!running.compareAndSet(false, true)) {
            log.info("Case report refresh skipped: the previous cycle is still running");
            return;
        }
        LocalDate today = LocalDate.now(BANGKOK);
        worker.submit(() -> {
            try {
                refreshAll(today);
            } finally {
                running.set(false);
            }
        });
    }

    /** One cycle: every sheet for {@code day}, each on its own so one failure costs one sheet. */
    void refreshAll(LocalDate day) {
        long started = System.nanoTime();
        int refreshed = 0;
        for (String code : CaseReportDefinition.SHEETS) {
            try {
                int rows = runService.rowsFor(code, day, true).size();
                refreshed++;
                log.debug("Refreshed the {} report for {}: {} rows", code, day, rows);
            } catch (BadRequestException e) {
                // Sent already, or the cycle ran past midnight and the day is over: both
                // are the service saying "this date is settled", not failures.
                log.info("Did not refresh the {} report for {}: {}", code, day, e.getMessage());
            } catch (Exception e) {
                log.error("Refreshing the {} report for {} failed; the stored sheet stands "
                        + "until the next cycle.", code, day, e);
            }
        }
        log.info("Case report refresh for {}: {} of {} sheets in {} s", day, refreshed,
                CaseReportDefinition.SHEETS.size(), Duration.ofNanos(System.nanoTime() - started).toSeconds());

        // AOT's Google Sheet in the same cycle, so it is never more than one cycle behind
        // the boards. It skips itself when its sync is off or it ran moments ago (the AOT
        // tab also syncs it on open).
        try {
            AotSheetAutoSync.Outcome sheet = aotSheet.syncIfDue(Duration.ofMinutes(1));
            if (sheet.synced()) {
                log.info("AOT sheet synced with the refresh: {} rows", sheet.result().seen());
            }
        } catch (Exception e) {
            log.error("Syncing the AOT sheet with the refresh failed; the stored copy stands.", e);
        }
    }
}
