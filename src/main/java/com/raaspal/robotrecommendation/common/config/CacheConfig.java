package com.raaspal.robotrecommendation.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Enables method-level caching (backed by Caffeine, an in-memory store).
 *
 * <p>Why we cache: rendering a monthly report re-runs several DB queries and an
 * in-memory aggregation on every view ({@code ReportPreviewService.build}). When
 * many customers open their report links at once, that repeated work both slows
 * responses and consumes scarce DB connections. A cache hit returns the already
 * computed report and touches the database zero times.
 *
 * <p>Why Caffeine (not the default map): Spring's default cache is an unbounded
 * {@code ConcurrentHashMap} — it never expires entries and never limits its
 * size, so it leaks memory and serves stale data forever. Caffeine adds:
 * <ul>
 *   <li><b>expireAfterWrite (TTL)</b> — an entry auto-expires N minutes after it
 *       was cached. This is a safety net against staleness; the primary freshness
 *       mechanism is explicit eviction when telemetry is re-synced.</li>
 *   <li><b>maximumSize</b> — bounds memory. Past the limit, Caffeine evicts the
 *       least-recently-used entries. A report object is small, so a few thousand
 *       entries is cheap.</li>
 * </ul>
 *
 * <p>The report for a <em>past</em> month is immutable (its telemetry won't
 * change), so caching it is always safe. The current month can still be synced,
 * which is why {@code TelemetrySyncService} evicts this cache on every sync.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Cache holding computed per-robot monthly reports, keyed by "serial|month". */
    public static final String ROBOT_MONTHLY_REPORTS = "robotMonthlyReports";

    @Value("${app.cache.report.ttl-minutes:30}")
    private long ttlMinutes;

    @Value("${app.cache.report.max-size:2000}")
    private long maxSize;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(ROBOT_MONTHLY_REPORTS);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .maximumSize(maxSize));
        return manager;
    }
}
