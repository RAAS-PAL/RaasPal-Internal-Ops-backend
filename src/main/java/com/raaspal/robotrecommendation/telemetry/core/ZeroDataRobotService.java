package com.raaspal.robotrecommendation.telemetry.core;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.report.service.CustomerReportExclusionService;
import com.raaspal.robotrecommendation.report.service.ReportPeriod;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.telemetry.dto.ZeroDataRobotsResponse;
import com.raaspal.robotrecommendation.telemetry.dto.ZeroDataRobotsResponse.ContractStatus;
import com.raaspal.robotrecommendation.telemetry.dto.ZeroDataRobotsResponse.Reason;
import com.raaspal.robotrecommendation.telemetry.entity.ZeroDataFollowup;
import com.raaspal.robotrecommendation.telemetry.repository.RobotTaskReportRepository;
import com.raaspal.robotrecommendation.telemetry.repository.ZeroDataFollowupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The robots that should have reported a month and logged nothing — as the customer
 * success team's monthly worklist.
 *
 * <p>After the nightly sync has run for a whole month, a robot with zero tasks is one
 * of three things, and the three need different people: <em>never synced</em> (no task
 * has ever arrived — check the serial number and brand), <em>sync failing</em> (we
 * tried and our side broke — nobody should call the customer about that), or <em>no
 * tasks</em> (synced fine, genuinely nothing — the robot was off, in storage, or its
 * contract is over). The list says which, per robot, from the sync outcome the loop now
 * records on each unit.
 *
 * <p>The list is computed when asked, never recorded at sync time — a flag would be
 * wrong the moment a late backfill landed. What <em>is</em> stored is the follow-up:
 * one {@link ZeroDataFollowup} per robot per month, overlaid on the list, so on the
 * 5th someone can see "12 zero, 9 contacted, 3 outstanding" rather than a bare list.
 *
 * <p>"Should have reported" means an active deployment whose contract overlaps the
 * month — {@link ReportPeriod#coversContract}, the same test the bundle uses, so a
 * robot whose contract ended before the month is not listed as missing data it was
 * never meant to have.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZeroDataRobotService {

    /** A contract ending within this many days is flagged as ending soon. */
    public static final int ENDING_SOON_DAYS = 30;

    private final DeploymentRepository deploymentRepository;
    private final RobotTaskReportRepository taskReportRepository;
    private final ZeroDataFollowupRepository followupRepository;
    private final CustomerReportExclusionService exclusionService;

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
        Map<UUID, ZeroDataFollowup> followups = new HashMap<>();
        for (ZeroDataFollowup f : followupRepository.findByReportMonth(month)) {
            followups.put(f.getRobotUnitId(), f);
        }

        List<Deployment> deployments = deploymentRepository.findActiveWithRobotAndCustomer();
        int inScope = 0;
        List<ZeroDataRobotsResponse.Robot> robots = new ArrayList<>();
        Map<UUID, Set<UUID>> exclusionsByCustomer = new HashMap<>();

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
            Long daysToEnd = d.getContractEndDate() == null
                    ? null : ChronoUnit.DAYS.between(today, d.getContractEndDate());
            ZeroDataFollowup f = followups.get(r.getId());
            UUID customerId = d.getCustomerProfile().getId();
            Set<UUID> excluded = exclusionsByCustomer.computeIfAbsent(
                    customerId, id -> exclusionService.get(id, month));

            robots.add(new ZeroDataRobotsResponse.Robot(
                    r.getId(),
                    r.getSerialNumber(),
                    r.getName(),
                    r.getBrand(),
                    r.getModel(),
                    customerId,
                    d.getCustomerProfile().getCompanyName(),
                    d.getSite(),
                    d.getContractStartDate(),
                    d.getContractEndDate(),
                    contractStatus(d.getContractEndDate(), today),
                    daysToEnd,
                    lastDate,
                    daysSince,
                    r.getLastSyncAttemptAt(),
                    r.getLastSyncSuccessAt(),
                    r.getLastSyncError(),
                    reason(r, last, period, zone),
                    f == null ? null : f.getStatus(),
                    f == null ? null : f.getOutcome(),
                    f == null ? null : f.getNote(),
                    f == null ? null : f.getUpdatedBy(),
                    f == null ? null : f.getUpdatedAt(),
                    excluded.contains(r.getId())));
        }

        // Ours-to-fix first (sync failing), then never-synced, then the longest silent.
        robots.sort(Comparator
                .comparing((ZeroDataRobotsResponse.Robot x) ->
                        x.reason() == Reason.SYNC_FAILING ? 0 : x.reason() == Reason.NEVER_SYNCED ? 1 : 2)
                .thenComparing(x -> x.lastDataDate(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ZeroDataRobotsResponse.Robot::customerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ZeroDataRobotsResponse.Robot::serialNumber));

        int resolved = 0, contacted = 0;
        for (ZeroDataRobotsResponse.Robot x : robots) {
            if (x.followupStatus() == ZeroDataFollowup.Status.RESOLVED) resolved++;
            else if (x.followupStatus() == ZeroDataFollowup.Status.CONTACTED) contacted++;
        }
        int toContact = robots.size() - resolved - contacted;

        log.info("Zero-data check for {}: {} of {} in-contract robots logged nothing ({} to contact, {} contacted, {} resolved)",
                month, robots.size(), inScope, toContact, contacted, resolved);

        return new ZeroDataRobotsResponse(month, period.label(), inScope, robots.size(),
                toContact, contacted, resolved, robots);
    }

    /**
     * Records what was done about one entry. Creates the row the first time, updates it
     * after. A resolution needs an outcome; anything else may leave it blank.
     */
    @Transactional
    public ZeroDataFollowup saveFollowup(UUID robotUnitId, String month, ZeroDataFollowup.Status status,
                                         ZeroDataFollowup.Outcome outcome, String note, String updatedBy) {
        ReportPeriod.ofMonth(month); // validates the key
        if (status == null) {
            throw new BadRequestException("A follow-up status is required");
        }
        if (status == ZeroDataFollowup.Status.RESOLVED && outcome == null) {
            throw new BadRequestException("A resolved follow-up needs an outcome");
        }
        ZeroDataFollowup f = followupRepository.findByRobotUnitIdAndReportMonth(robotUnitId, month)
                .orElseGet(() -> ZeroDataFollowup.builder().robotUnitId(robotUnitId).reportMonth(month).build());
        f.setStatus(status);
        f.setOutcome(outcome);
        f.setNote(note == null || note.isBlank() ? null : note.strip());
        f.setUpdatedBy(updatedBy);
        return followupRepository.save(f);
    }

    /**
     * Holds this robot back from its customer's report for the month — the existing
     * per-robot exclusion, reached from the worklist so nobody has to find the customer
     * in another tab while the reason is still being found out. Idempotent.
     */
    @Transactional
    public void excludeFromReport(UUID robotUnitId, String month) {
        ReportPeriod.ofMonth(month);
        Deployment d = deploymentRepository.findActiveWithRobotAndCustomer().stream()
                .filter(x -> x.getRobotUnit().getId().equals(robotUnitId))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Robot has no active deployment"));
        UUID customerId = d.getCustomerProfile().getId();
        Set<UUID> excluded = new LinkedHashSet<>(exclusionService.get(customerId, month));
        excluded.add(robotUnitId);
        exclusionService.replace(customerId, month, excluded);
    }

    /**
     * The three-way reason. A sync attempted within the period whose last outcome was a
     * failure is ours; no success ever is a registration question; otherwise the robot
     * really logged nothing.
     */
    private static Reason reason(RobotUnit r, Instant lastData, ReportPeriod period, ZoneId zone) {
        boolean failing = r.getLastSyncError() != null
                && r.getLastSyncAttemptAt() != null
                && !r.getLastSyncAttemptAt().isBefore(period.startInstant(zone));
        if (failing) return Reason.SYNC_FAILING;
        if (lastData == null && r.getLastSyncSuccessAt() == null) return Reason.NEVER_SYNCED;
        return Reason.NO_TASKS;
    }

    public static ContractStatus contractStatus(LocalDate end, LocalDate today) {
        if (end == null) return ContractStatus.NONE;
        if (end.isBefore(today)) return ContractStatus.ENDED;
        if (!end.isAfter(today.plusDays(ENDING_SOON_DAYS))) return ContractStatus.ENDING_SOON;
        return ContractStatus.ACTIVE;
    }
}
