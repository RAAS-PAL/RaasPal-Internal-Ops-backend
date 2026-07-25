package com.raaspal.robotrecommendation.telemetry.scheduler;

import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps {@code robot_task_reports} current by pulling each actively deployed
 * robot's task reports from its brand API on a schedule. This is what makes the
 * report pages and the partner API serve fresh data instead of whatever was
 * last synced by hand.
 *
 * <p><strong>Disabled unless {@code TELEMETRY_SYNC_ENABLED=true}</strong>, so
 * deploying this cannot start calling brand APIs unexpectedly; on-demand sync
 * keeps working either way.
 *
 * <p>Two deliberate choices:
 * <ul>
 *   <li><strong>Look-back window</strong> (default 3 days) rather than "yesterday
 *       only" — brand APIs backfill late-arriving tasks, and a robot offline for a
 *       day would otherwise lose its data permanently. Re-syncing is safe because
 *       {@link TelemetrySyncService} dedupes on external task id.</li>
 *   <li><strong>Non-overlapping</strong> — a run that outlives its interval will
 *       not stack up behind itself; the next tick is skipped instead.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.telemetry.sync-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TelemetrySyncScheduler {

    private final TelemetrySyncService telemetrySyncService;

    @Value("${app.telemetry.sync-zone}")
    private String zone;

    /** How many days back each run re-checks, to catch late-arriving tasks. */
    @Value("${app.telemetry.sync-lookback-days}")
    private int lookbackDays;

    /** Guards against a slow run overlapping the next scheduled tick. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${app.telemetry.sync-cron}", zone = "${app.telemetry.sync-zone}")
    public void syncRecent() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Telemetry sync skipped — the previous run is still in progress");
            return;
        }
        try {
            // Dates are resolved in the same zone the cron fires in, so "today"
            // means the local operating day rather than a UTC one.
            LocalDate today = LocalDate.now(ZoneId.of(zone));
            LocalDate from = today.minusDays(Math.max(lookbackDays, 1) - 1L);
            telemetrySyncService.syncAllActive(from, today);
        } catch (Exception e) {
            // Never let a scheduled run throw — that would kill the schedule.
            log.error("Scheduled telemetry sync failed: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }
}
