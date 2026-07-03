package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.common.config.CacheConfig;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Caching boundary in front of {@link ReportPreviewService}. Kept as a separate
 * bean on purpose:
 *
 * <ul>
 *   <li><b>Cache sits outside the transaction.</b> {@code ReportPreviewService.build}
 *       is {@code @Transactional}; a cache hit here returns without ever calling
 *       it, so a hit opens no DB transaction and borrows no connection.</li>
 *   <li><b>No self-invocation trap.</b> Spring's cache proxy only intercepts
 *       calls from outside the bean, so the annotation must live on a bean the
 *       callers invoke — this one — not on {@code build} called internally.</li>
 *   <li><b>Selective.</b> Public report views go through here (cached); the admin
 *       preview keeps calling {@code ReportPreviewService.build} directly so it
 *       always reflects a fresh sync.</li>
 * </ul>
 *
 * The cache key is {@code serialNumber|month}, so each robot+month is computed
 * once and reused until it expires (TTL) or is evicted (on telemetry sync).
 */
@Service
@RequiredArgsConstructor
public class ReportCacheService {

    private final ReportPreviewService reportPreviewService;

    /** Returns the (cached) computed report for one robot and month. */
    @Cacheable(cacheNames = CacheConfig.ROBOT_MONTHLY_REPORTS, key = "#serialNumber + '|' + #month")
    public ReportPreviewResponse getRobotReport(String serialNumber, String month) {
        return reportPreviewService.build(serialNumber, month);
    }

    /**
     * Drops every cached report. Called after telemetry is synced, since new task
     * data can change any month's figures. Clearing all is fine: the cache is
     * cheap to refill and syncs are infrequent relative to views.
     */
    @CacheEvict(cacheNames = CacheConfig.ROBOT_MONTHLY_REPORTS, allEntries = true)
    public void evictAll() {
        // no body — the annotation does the work
    }
}
