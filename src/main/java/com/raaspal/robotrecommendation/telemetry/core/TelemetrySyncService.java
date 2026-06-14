package com.raaspal.robotrecommendation.telemetry.core;

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
        log.info("Telemetry sync complete");
    }

    @Transactional
    public void syncRobotUnit(RobotUnit robotUnit, LocalDate from, LocalDate to) {
        List<Deployment> deployments = deploymentRepository.findByRobotUnitIdAndIsActiveTrue(robotUnit.getId());
        if (deployments.isEmpty()) {
            log.warn("Skipping robot unit {} - no active deployment", robotUnit.getSerialNumber());
            return;
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
    }
}
