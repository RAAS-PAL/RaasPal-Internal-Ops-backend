package com.raaspal.robotrecommendation.kpi.scheduler;

import com.raaspal.robotrecommendation.kpi.entity.CaseTicketSyncRun;
import com.raaspal.robotrecommendation.kpi.service.MondayCaseSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes {@code kpi_case_ticket} from monday on a schedule so the KPI dashboard
 * is current each morning without anyone pressing the button.
 *
 * <p><strong>Disabled unless {@code KPI_MONDAY_SYNC_ENABLED=true}</strong>, the
 * same off-switch pattern as the telemetry sync: deploying this must not start
 * spending monday's daily API budget on its own, and during the Render →
 * Lightsail parallel run only one machine should be syncing.
 *
 * <p>A run that is still going when the next tick fires is skipped, not
 * queued — the service holds one lock for scheduled and manual runs alike.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.kpi.monday.sync-enabled", havingValue = "true")
@RequiredArgsConstructor
public class MondayCaseSyncScheduler {

    private final MondayCaseSyncService syncService;

    @Scheduled(cron = "${app.kpi.monday.sync-cron}", zone = "${app.kpi.monday.sync-zone}")
    public void syncBoards() {
        try {
            syncService.syncAll(CaseTicketSyncRun.Trigger.SCHEDULED);
        } catch (IllegalStateException e) {
            log.warn("Scheduled monday case sync skipped — {}", e.getMessage());
        } catch (Exception e) {
            // Never let a scheduled run throw — that would kill the schedule.
            log.error("Scheduled monday case sync failed: {}", e.getMessage(), e);
        }
    }
}
