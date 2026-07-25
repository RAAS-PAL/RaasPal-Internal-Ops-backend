package com.raaspal.robotrecommendation.telemetry.core;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.report.service.ReportCacheService;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import com.raaspal.robotrecommendation.telemetry.repository.RobotTaskReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Syncs robot task reports from each robot's brand telemetry API into
 * {@link RobotTaskReport}, deduplicating on external task id.
 *
 * <p>Two entry points: {@link #syncBySerialNumber} for one robot on demand, and
 * {@link #syncAllActive} for every actively deployed robot (the scheduled
 * pipeline). Both are <em>idempotent</em> — re-syncing an overlapping window
 * never duplicates rows, which is what makes a look-back window safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetrySyncService {

    private final RobotUnitRepository robotUnitRepository;
    private final DeploymentRepository deploymentRepository;
    private final TelemetryAdapterRegistry adapterRegistry;
    private final RobotTaskReportRepository taskReportRepository;
    private final ReportCacheService reportCacheService;

    /** Result of a sync run for one robot. */
    public record SyncResult(String serialNumber, int saved, int skipped) {
    }

    /** Aggregate outcome of a fleet-wide sync run. */
    public record SyncSummary(
            LocalDate from,
            LocalDate to,
            int robotsSynced,
            int robotsSkipped,
            int robotsFailed,
            int saved,
            int duplicatesSkipped,
            long durationMs) {
    }

    /**
     * Syncs one robot (by serial number) from its brand telemetry API for the
     * given date range, on demand. Brand telemetry failures (e.g. credentials
     * not configured, auth, robot not bound to the account) surface as a
     * {@link BadRequestException} with the underlying message.
     */
    @Transactional
    public SyncResult syncBySerialNumber(String serialNumber, LocalDate from, LocalDate to) {
        RobotUnit robotUnit = robotUnitRepository.findBySerialNumber(serialNumber)
                .orElseThrow(() -> new ResourceNotFoundException("RobotUnit", "serialNumber", serialNumber));
        try {
            SyncResult result = syncRobotUnit(robotUnit, from, to);
            reportCacheService.evictAll(); // new task data → cached reports may be stale
            return result;
        } catch (Exception e) {
            throw new BadRequestException("Telemetry sync failed for " + serialNumber + ": " + e.getMessage());
        }
    }

    /**
     * Syncs every actively deployed robot for {@code [from, to]} — the scheduled
     * pipeline's workhorse.
     *
     * <p>Deliberately <strong>not</strong> {@code @Transactional}: a fleet sync is
     * a long sequence of external HTTP calls, and holding one database
     * transaction open across all of them would pin a connection for minutes.
     * Each robot's rows are instead persisted in their own transaction (a single
     * {@code saveAll}), so one robot's failure can never roll back another's data.
     *
     * <p>Robots whose brand has no adapter, or whose adapter is not configured,
     * are skipped and reported once per brand rather than failing per robot.
     */
    public SyncSummary syncAllActive(LocalDate from, LocalDate to) {
        long startedAt = System.currentTimeMillis();

        // One query returns exactly the robots worth syncing (active deployments)
        // with their robot and customer already fetched — no per-robot lookups.
        Map<UUID, Deployment> byRobotId = new LinkedHashMap<>();
        for (Deployment deployment : deploymentRepository.findActiveWithRobotAndCustomer()) {
            byRobotId.putIfAbsent(deployment.getRobotUnit().getId(), deployment);
        }

        int synced = 0;
        int skipped = 0;
        int failed = 0;
        int saved = 0;
        int duplicates = 0;
        Set<String> unusableBrands = new HashSet<>();

        log.info("Telemetry sync starting for {} actively deployed robot(s), range {} to {}",
                byRobotId.size(), from, to);

        for (Deployment deployment : byRobotId.values()) {
            RobotUnit robot = deployment.getRobotUnit();
            String brand = robot.getBrand();

            Optional<TelemetryAdapter> adapter = usableAdapter(brand, unusableBrands);
            if (adapter.isEmpty()) {
                skipped++;
                continue;
            }

            try {
                SyncResult result = persistFetched(
                        robot,
                        deployment.getCustomerProfile(),
                        adapter.get().fetchTaskReports(robot.getSerialNumber(), from, to));
                saved += result.saved();
                duplicates += result.skipped();
                synced++;
            } catch (Exception e) {
                // Isolate per robot: a single robot's API failure must not stop the fleet.
                failed++;
                log.error("Telemetry sync failed for robot {} ({}): {}",
                        robot.getSerialNumber(), brand, e.getMessage());
            }
        }

        if (saved > 0) {
            reportCacheService.evictAll(); // new task data → cached reports may be stale
        }

        SyncSummary summary = new SyncSummary(from, to, synced, skipped, failed, saved, duplicates,
                System.currentTimeMillis() - startedAt);
        log.info("Telemetry sync complete: {} robot(s) synced, {} skipped, {} failed — "
                        + "{} new report(s), {} duplicate(s), took {} ms",
                synced, skipped, failed, saved, duplicates, summary.durationMs());
        return summary;
    }

    /**
     * The adapter for a brand if it can actually be used right now. Unsupported
     * and unconfigured brands are logged once per run (tracked in
     * {@code alreadyReported}) so a fleet of one unconfigured brand produces a
     * single line, not one per robot.
     */
    private Optional<TelemetryAdapter> usableAdapter(String brand, Set<String> alreadyReported) {
        Optional<TelemetryAdapter> adapter = adapterRegistry.findAdapter(brand);
        if (adapter.isEmpty()) {
            if (alreadyReported.add(brand)) {
                log.info("Skipping brand '{}' — no telemetry adapter registered", brand);
            }
            return Optional.empty();
        }
        if (!adapter.get().isConfigured()) {
            if (alreadyReported.add(brand)) {
                log.warn("Skipping brand '{}' — telemetry API credentials are not configured", brand);
            }
            return Optional.empty();
        }
        return adapter;
    }

    /** Syncs a single robot: fetch from its brand API, then persist what is new. */
    @Transactional
    public SyncResult syncRobotUnit(RobotUnit robotUnit, LocalDate from, LocalDate to) {
        List<Deployment> deployments = deploymentRepository.findByRobotUnitIdAndIsActiveTrue(robotUnit.getId());
        if (deployments.isEmpty()) {
            log.warn("Skipping robot unit {} - no active deployment", robotUnit.getSerialNumber());
            return new SyncResult(robotUnit.getSerialNumber(), 0, 0);
        }

        TelemetryAdapter adapter = adapterRegistry.getAdapter(robotUnit.getBrand());
        return persistFetched(
                robotUnit,
                deployments.get(0).getCustomerProfile(),
                adapter.fetchTaskReports(robotUnit.getSerialNumber(), from, to));
    }

    /**
     * Stores the task reports that are not already present. Dedup is a single
     * {@code IN} query over the whole fetched batch followed by one
     * {@code saveAll}, rather than an exists-check and insert per report — which
     * matters when a busy robot returns hundreds of tasks for a month.
     */
    private SyncResult persistFetched(RobotUnit robotUnit,
                                      CustomerProfile customer,
                                      List<TelemetryTaskReport> fetched) {
        String serialNumber = robotUnit.getSerialNumber();
        if (fetched.isEmpty()) {
            log.debug("Synced robot unit {}: brand API returned no task reports", serialNumber);
            return new SyncResult(serialNumber, 0, 0);
        }

        List<String> externalIds = fetched.stream()
                .map(TelemetryTaskReport::getExternalTaskId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Set<String> existing = externalIds.isEmpty()
                ? Set.of()
                : new HashSet<>(taskReportRepository.findExistingExternalTaskIds(externalIds));

        List<RobotTaskReport> toSave = new ArrayList<>();
        Set<String> seenInBatch = new HashSet<>();
        int skipped = 0;
        Instant syncedAt = Instant.now();

        for (TelemetryTaskReport report : fetched) {
            String externalId = report.getExternalTaskId();
            // Skip duplicates already stored, and repeats inside this same batch
            // (a paginated brand API can return an overlapping row twice).
            if (externalId == null || existing.contains(externalId) || !seenInBatch.add(externalId)) {
                skipped++;
                continue;
            }
            // report_month is NOT NULL and is derived from start time, so a task
            // without one cannot be stored — count it rather than failing the run.
            if (report.getStartTime() == null) {
                skipped++;
                log.debug("Skipping task {} for robot {} — no start time", externalId, serialNumber);
                continue;
            }

            RobotTaskReport entity = report.toEntity();
            entity.setRobotUnit(robotUnit);
            entity.setCustomerProfile(customer);
            entity.setReportMonth(YearMonth.from(report.getStartTime().atZone(ZoneOffset.UTC)).toString());
            entity.setSyncedAt(syncedAt);
            toSave.add(entity);
        }

        if (!toSave.isEmpty()) {
            taskReportRepository.saveAll(toSave);
        }
        log.info("Synced robot unit {}: {} saved, {} duplicate(s) skipped", serialNumber, toSave.size(), skipped);
        return new SyncResult(serialNumber, toSave.size(), skipped);
    }
}
