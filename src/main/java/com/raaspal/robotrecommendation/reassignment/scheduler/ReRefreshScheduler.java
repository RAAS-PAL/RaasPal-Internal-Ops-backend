package com.raaspal.robotrecommendation.reassignment.scheduler;

import com.raaspal.robotrecommendation.reassignment.service.ReTicketRefreshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the RE queue current by re-reading the board's open tickets on a schedule.
 *
 * <p>Off unless {@code app.re-assignment.refresh.enabled}. The refresh only writes the
 * module's own tables ({@code re_ticket}, assignment status), but local development shares
 * the production database, so like every scheduler here it runs in one place only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.re-assignment.refresh.enabled", havingValue = "true")
public class ReRefreshScheduler {

    private final ReTicketRefreshService refresh;

    @Scheduled(cron = "${app.re-assignment.refresh.cron:0 */10 7-20 * * *}",
            zone = "${app.re-assignment.refresh.zone:Asia/Bangkok}")
    public void run() {
        try {
            refresh.refresh();
        } catch (Exception e) {
            log.warn("Scheduled RE refresh failed: {}", e.getMessage());
        }
    }
}
