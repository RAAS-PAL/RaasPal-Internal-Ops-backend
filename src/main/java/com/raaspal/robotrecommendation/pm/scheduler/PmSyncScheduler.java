package com.raaspal.robotrecommendation.pm.scheduler;

import com.raaspal.robotrecommendation.pm.service.MondayPmSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes the PM mirror overnight.
 *
 * <p>Off unless {@code app.pm.monday.sync-enabled} is true, so a developer running
 * locally does not quietly start calling the monday API on a shared token - and,
 * because local and production share a database, writing to production data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.pm.monday.sync-enabled", havingValue = "true")
public class PmSyncScheduler {

    private final MondayPmSyncService syncService;

    @Scheduled(cron = "${app.pm.monday.sync-cron}", zone = "${app.pm.monday.sync-zone}")
    public void sync() {
        log.info("Scheduled monday PM sync starting");
        MondayPmSyncService.SyncSummary summary = syncService.syncAll("scheduler");
        log.info("Scheduled monday PM sync finished: {} board(s), {} contracts, {} visits, {} failure(s) - {}",
                summary.boards(), summary.contractsWritten(), summary.visitsWritten(), summary.failures(),
                summary.messages());
    }
}
