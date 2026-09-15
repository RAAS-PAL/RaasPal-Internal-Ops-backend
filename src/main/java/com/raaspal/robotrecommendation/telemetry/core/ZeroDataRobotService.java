package com.raaspal.robotrecommendation.telemetry.core;

import com.raaspal.robotrecommendation.report.service.ReportPeriod;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.telemetry.dto.ZeroDataRobotsResponse;
import com.raaspal.robotrecommendation.telemetry.repository.RobotTaskReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The robots that should have reported a month and logged nothing.
 *
 * <p>After the nightly sync has run for a whole month, a robot with zero tasks is one
 * of three things: offline the entire time, never actually connected to its brand
 * cloud, or wrongly registered (typo in the serial, wrong brand). All three look
 * identical on the monthly report — a page of zeros — and none is the customer's
 * concern. This list is the operator's: it puts every such robot in one place so
 * somebody can find out which of the three it is before the report goes out.
 *
 * <p>Computed when asked, not recorded at sync time. A flag written by the sync would
 * be wrong the moment a late backfill landed or a robot was re-registered; a query is
 * right every time it runs, and costs two aggregate reads.
 *
 * <p>"Should have reported" means: an active deployment whose contract overlaps the
 * month — the same {@link ReportPeriod#coversContract} test the bundle uses, so a
 * robot whose contract ended before the month is not listed as missing data it was
 * never meant to have.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZeroDataRobotService {

    private final DeploymentRepository deploymentRepository;
    private final RobotTaskReportRepository taskReportRepository;

    @Value("${app.reports.business-zone:Asia/Bangkok}")
    private String businessZone;

    @Transactional(readOnly = true)
    public ZeroDataRobotsResponse forMonth(String month) {
        ReportPeriod period = ReportPeriod.ofMonth(month);
        ZoneId zone = ZoneId.of(businessZone);
        LocalDate today = LocalDate.now(zone);

        // Which robots logged anything this month, and when each robot last logged anything at all.
        Set<UUID> withData = new HashSet<>();
        for (Object[] row : taskReportRepository.countByRobotForMonth(month)) {
            withData.add((UUID) row[0]);
        }
        Map<UUID, Instant> lastDataAt = new HashMap<>();
        for (Object[] row : taskReportRepository.lastStartTimeByRobot()) {
            lastDataAt.put((UUID) row[0], (Instant) row[1]);
        }

        List<Deployment> deployments = deploymentRepository.findActiveWithRobotAndCustomer();
        int inScope = 0;
        List<ZeroDataRobotsResponse.Robot> robots = new java.util.ArrayList<>();

        for (Deployment d : deployments) {
            if (!period.coversContract(d.getContractStartDate(), d.getContractEndDate())) {
                continue;
            }
            inScope++;
            RobotUnit r = d.getRobotUnit();
            if (withData.contains(r.getId())) {
                continue;
            }

            Instant last = lastDataAt.get(r.getId());
            LocalDate lastDate = last == null ? null : last.atZone(zone).toLocalDate();
            Long daysSince = lastDate == null ? null : ChronoUnit.DAYS.between(lastDate, today);

            robots.add(new ZeroDataRobotsResponse.Robot(
                    r.getId(),
                    r.getSerialNumber(),
                    r.getName(),
                    r.getBrand(),
                    r.getModel(),
                    d.getCustomerProfile().getId(),
                    d.getCustomerProfile().getCompanyName(),
                    d.getSite(),
                    d.getContractStartDate(),
                    d.getContractEndDate(),
                    lastDate,
                    daysSince,
                    last == null ? "Never synced any task" : "No tasks this month"));
        }

        // Never-synced first (the likelier registration problems), then the longest silent.
        robots.sort(Comparator
                .comparing((ZeroDataRobotsResponse.Robot x) -> x.lastDataDate() != null)
                .thenComparing(x -> x.lastDataDate(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ZeroDataRobotsResponse.Robot::customerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ZeroDataRobotsResponse.Robot::serialNumber));

        log.info("Zero-data check for {}: {} of {} in-contract robots logged nothing",
                month, robots.size(), inScope);

        return new ZeroDataRobotsResponse(month, period.label(), inScope, robots.size(), robots);
    }
}
