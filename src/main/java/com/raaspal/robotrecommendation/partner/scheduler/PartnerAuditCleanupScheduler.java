package com.raaspal.robotrecommendation.partner.scheduler;

import com.raaspal.robotrecommendation.partner.repository.PartnerApiAccessLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Enforces retention on the partner access audit. The audit table gains a row per
 * partner request, so without pruning it grows forever — this keeps the trade-off
 * of a queryable audit honest by bounding it in time.
 *
 * <p>Runs nightly and deletes rows older than
 * {@code app.partner.audit-retention-days}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.partner.audit-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class PartnerAuditCleanupScheduler {

    private final PartnerApiAccessLogRepository accessLogRepository;

    @Value("${app.partner.audit-retention-days:90}")
    private int retentionDays;

    @Scheduled(cron = "${app.partner.audit-cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void deleteExpiredAuditRows() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(retentionDays, 1));
            int deleted = accessLogRepository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("Pruned {} partner API access log row(s) older than {}", deleted, cutoff);
            }
        } catch (Exception e) {
            // Never let a scheduled run throw — that would kill the schedule.
            log.error("Partner audit cleanup failed: {}", e.getMessage(), e);
        }
    }
}
