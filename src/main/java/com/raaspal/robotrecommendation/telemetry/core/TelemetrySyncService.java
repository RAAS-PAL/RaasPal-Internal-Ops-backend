package com.raaspal.robotrecommendation.telemetry.core;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
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
import java.util.List;

/**
 * Syncs robot task reports from each robot's brand telemetry API into
 * {@link RobotTaskReport}, deduplicating on external task id.
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

    /** Syncs yesterday's task reports for every robot unit. */
    public void syncYesterday() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        syncRange(yesterday, yesterday);
    }

    /** Syncs task reports whose start time falls within {@code [from, to]} for every robot unit. */
    public void syncRange(LocalDate from, LocalDate to) {
        List<RobotUnit> robotUnits = robotUnitRepository.findAll();
        log.info("Starting telemetry sync for {} robot unit(s), range {} to {}", robotUnits.size(), from, to);
        for (RobotUnit robotUnit : robotUnits) {
            try {
                syncRobotUnit(robotUnit, from, to);
            } catch (Exception e) {
                log.error("Telemetry sync failed for robot unit {} ({}): {}",
                        robotUnit.getSerialNumber(), robotUnit.getBrand(), e.getMessage(), e);
            }
        }
        reportCacheService.evictAll(); // new task data → cached reports may be stale
        log.info("Telemetry sync complete");
    }

    @Transactional
    public SyncResult syncRobotUnit(RobotUnit robotUnit, LocalDate from, LocalDate to) {
        List<Deployment> deployments = deploymentRepository.findByRobotUnitIdAndIsActiveTrue(robotUnit.getId());
        if (deployments.isEmpty()) {
            log.warn("Skipping robot unit {} - no active deployment", robotUnit.getSerialNumber());
            return new SyncResult(robotUnit.getSerialNumber(), 0, 0);
        }

        TelemetryAdapter adapter = adapterRegistry.getAdapter(robotUnit.getBrand());
        List<TelemetryTaskReport> reports = adapter.fetchTaskReports(robotUnit.getSerialNumber(), from, to);

        int saved = 0;
        int skipped = 0;
        for (TelemetryTaskReport report : reports) {
            if (taskReportRepository.existsByExternalTaskId(report.getExternalTaskId())) {
                skipped++;
                continue;
            }
            RobotTaskReport entity = report.toEntity();
            entity.setRobotUnit(robotUnit);
            entity.setCustomerProfile(deployments.get(0).getCustomerProfile());
            entity.setReportMonth(YearMonth.from(report.getStartTime().atZone(ZoneOffset.UTC)).toString());
            entity.setSyncedAt(Instant.now());
            taskReportRepository.save(entity);
            saved++;
        }
        log.info("Synced robot unit {}: {} saved, {} duplicate(s) skipped", robotUnit.getSerialNumber(), saved, skipped);
        return new SyncResult(robotUnit.getSerialNumber(), saved, skipped);
    }
}
