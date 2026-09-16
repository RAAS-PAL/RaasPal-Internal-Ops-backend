package com.raaspal.robotrecommendation.telemetry;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.report.service.CustomerReportExclusionService;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import com.raaspal.robotrecommendation.telemetry.core.ZeroDataRobotService;
import com.raaspal.robotrecommendation.telemetry.dto.ZeroDataRobotsResponse;
import com.raaspal.robotrecommendation.telemetry.dto.ZeroDataRobotsResponse.ContractStatus;
import com.raaspal.robotrecommendation.telemetry.dto.ZeroDataRobotsResponse.Reason;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import com.raaspal.robotrecommendation.telemetry.entity.ZeroDataFollowup;
import com.raaspal.robotrecommendation.telemetry.repository.RobotTaskReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The monthly worklist: which robots it names for a month, why, and what happens as
 * the customer success team works through it.
 *
 * <p>Six robots, one month (July 2026): one that worked, one that went quiet in June,
 * one that has never synced anything, one whose sync has been failing all July, one
 * whose contract ended in May, and one that is deactivated. Only the quiet, the
 * never-synced and the sync-failing belong on the list — and each for its own reason.
 */
@SpringBootTest
@Transactional
class ZeroDataRobotServiceTest {

    private static final String MONTH = "2026-07";
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final Instant IN_JULY = LocalDate.of(2026, 7, 20).atTime(0, 5).atZone(BANGKOK).toInstant();

    @Autowired private ZeroDataRobotService service;
    @Autowired private CustomerReportExclusionService exclusions;
    @Autowired private CustomerProfileRepository customerProfileRepository;
    @Autowired private RobotUnitRepository robotUnitRepository;
    @Autowired private DeploymentRepository deploymentRepository;
    @Autowired private RobotTaskReportRepository taskReportRepository;

    private CustomerProfile customer;
    private RobotUnit quiet;
    private RobotUnit never;
    private RobotUnit failing;

    @BeforeEach
    void setUp() {
        customer = customerProfileRepository.save(
                CustomerProfile.builder().companyName("Quiet Fleet Co").build());

        RobotUnit worked = robot("GS-ZD-WORKED");
        deploy(worked, true, null, null);
        task(worked, LocalDate.of(2026, 7, 10), "2026-07");

        quiet = robot("GS-ZD-QUIET");
        deploy(quiet, true, null, LocalDate.of(2026, 8, 10)); // ends soon-ish relative to July
        task(quiet, LocalDate.of(2026, 6, 14), "2026-06");   // last seen in June

        never = robot("GS-ZD-NEVER");
        deploy(never, true, LocalDate.of(2026, 7, 1), null);

        failing = robot("GS-ZD-FAILING");
        failing.setLastSyncAttemptAt(IN_JULY);
        failing.setLastSyncSuccessAt(LocalDate.of(2026, 6, 30).atStartOfDay(BANGKOK).toInstant());
        failing.setLastSyncError("HTTP 401 token expired");
        robotUnitRepository.save(failing);
        deploy(failing, true, null, null);
        task(failing, LocalDate.of(2026, 6, 28), "2026-06");

        RobotUnit ended = robot("GS-ZD-ENDED");
        deploy(ended, true, null, LocalDate.of(2026, 5, 31));   // not theirs in July

        RobotUnit inactive = robot("GS-ZD-INACTIVE");
        deploy(inactive, false, null, null);
    }

    @Test
    void listsInContractRobotsWithNoTasksThatMonthAndNothingElse() {
        ZeroDataRobotsResponse r = service.forMonth(MONTH);

        assertThat(r.monthLabel()).isEqualTo("July 2026");
        assertThat(r.robots()).extracting(ZeroDataRobotsResponse.Robot::serialNumber)
                .containsExactlyInAnyOrder("GS-ZD-QUIET", "GS-ZD-NEVER", "GS-ZD-FAILING");
        assertThat(r.zeroData()).isEqualTo(3);
        // worked + quiet + never + failing; not ended-in-May, not inactive
        assertThat(r.inScope()).isEqualTo(4);
    }

    /** The three reasons, told apart — the point of recording the sync outcome. */
    @Test
    void tellsASyncFailureFromANeverSyncedRobotFromAnIdleOne() {
        Map<String, ZeroDataRobotsResponse.Robot> byId = byId();

        assertThat(byId.get("GS-ZD-FAILING").reason()).isEqualTo(Reason.SYNC_FAILING);
        assertThat(byId.get("GS-ZD-FAILING").lastSyncError()).contains("401");

        assertThat(byId.get("GS-ZD-NEVER").reason()).isEqualTo(Reason.NEVER_SYNCED);
        assertThat(byId.get("GS-ZD-NEVER").lastDataDate()).isNull();

        assertThat(byId.get("GS-ZD-QUIET").reason()).isEqualTo(Reason.NO_TASKS);
        assertThat(byId.get("GS-ZD-QUIET").lastDataDate()).isEqualTo(LocalDate.of(2026, 6, 14));
        assertThat(byId.get("GS-ZD-QUIET").daysSinceLastData()).isPositive();
    }

    /** Ours-to-fix first, then registration questions, then the idle ones. */
    @Test
    void ordersOursFirst() {
        List<ZeroDataRobotsResponse.Robot> robots = service.forMonth(MONTH).robots();

        assertThat(robots).extracting(ZeroDataRobotsResponse.Robot::serialNumber)
                .containsExactly("GS-ZD-FAILING", "GS-ZD-NEVER", "GS-ZD-QUIET");
    }

    /** A follow-up overlays the computed list and the counts move with it. */
    @Test
    void followupsOverlayTheListAndCountUp() {
        ZeroDataRobotsResponse before = service.forMonth(MONTH);
        assertThat(before.toContact()).isEqualTo(3);
        assertThat(before.contacted()).isZero();
        assertThat(before.resolved()).isZero();

        service.saveFollowup(quiet.getId(), MONTH, ZeroDataFollowup.Status.CONTACTED, null,
                "Called Khun A, robot in storage since June", "cs.user");
        service.saveFollowup(never.getId(), MONTH, ZeroDataFollowup.Status.RESOLVED,
                ZeroDataFollowup.Outcome.REGISTRATION_ERROR, "Serial was typed wrong", "cs.user");

        ZeroDataRobotsResponse after = service.forMonth(MONTH);
        assertThat(after.toContact()).isEqualTo(1);
        assertThat(after.contacted()).isEqualTo(1);
        assertThat(after.resolved()).isEqualTo(1);

        ZeroDataRobotsResponse.Robot q = byId().get("GS-ZD-QUIET");
        assertThat(q.followupStatus()).isEqualTo(ZeroDataFollowup.Status.CONTACTED);
        assertThat(q.followupNote()).contains("storage");
        assertThat(q.followupUpdatedBy()).isEqualTo("cs.user");
        assertThat(q.followupUpdatedAt()).isNotNull();

        // Updating the same entry replaces it rather than adding a second row.
        service.saveFollowup(quiet.getId(), MONTH, ZeroDataFollowup.Status.RESOLVED,
                ZeroDataFollowup.Outcome.IN_STORAGE, "Confirmed", "cs.user");
        assertThat(service.forMonth(MONTH).resolved()).isEqualTo(2);
    }

    @Test
    void aResolutionNeedsAnOutcome() {
        assertThatThrownBy(() -> service.saveFollowup(quiet.getId(), MONTH,
                ZeroDataFollowup.Status.RESOLVED, null, null, "cs.user"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("outcome");
    }

    /** The worklist's exclude action is the existing per-robot exclusion, made reachable. */
    @Test
    void excludeFromReportHoldsTheRobotBackAndShowsIt() {
        assertThat(byId().get("GS-ZD-QUIET").excludedFromReport()).isFalse();

        service.excludeFromReport(quiet.getId(), MONTH);

        assertThat(byId().get("GS-ZD-QUIET").excludedFromReport()).isTrue();
        assertThat(exclusions.get(customer.getId(), MONTH)).contains(quiet.getId());
        // Idempotent, and does not disturb another robot's exclusion.
        service.excludeFromReport(quiet.getId(), MONTH);
        assertThat(exclusions.get(customer.getId(), MONTH)).hasSize(1);
    }

    /** Contract status rides along so the row can say "ends in N days" or "ended". */
    @Test
    void carriesContractStatus() {
        Map<String, ZeroDataRobotsResponse.Robot> byId = byId();

        assertThat(byId.get("GS-ZD-NEVER").contractStatus()).isEqualTo(ContractStatus.NONE);
        assertThat(byId.get("GS-ZD-NEVER").daysToContractEnd()).isNull();
        // GS-ZD-QUIET ends 2026-08-10 — in the past relative to today's date, so ENDED.
        assertThat(byId.get("GS-ZD-QUIET").contractStatus()).isEqualTo(ContractStatus.ENDED);
        assertThat(byId.get("GS-ZD-QUIET").daysToContractEnd()).isNegative();
    }

    @Test
    void contractStatusRule() {
        LocalDate today = LocalDate.of(2026, 9, 15);
        assertThat(ZeroDataRobotService.contractStatus(null, today)).isEqualTo(ContractStatus.NONE);
        assertThat(ZeroDataRobotService.contractStatus(LocalDate.of(2026, 9, 14), today)).isEqualTo(ContractStatus.ENDED);
        assertThat(ZeroDataRobotService.contractStatus(LocalDate.of(2026, 9, 15), today)).isEqualTo(ContractStatus.ENDING_SOON);
        assertThat(ZeroDataRobotService.contractStatus(LocalDate.of(2026, 10, 15), today)).isEqualTo(ContractStatus.ENDING_SOON);
        assertThat(ZeroDataRobotService.contractStatus(LocalDate.of(2026, 10, 16), today)).isEqualTo(ContractStatus.ACTIVE);
    }

    /** The robot that went quiet in June was fine in June. */
    @Test
    void theSameRobotIsNotOnTheListForAMonthItWorked() {
        assertThat(service.forMonth("2026-06").robots())
                .extracting(ZeroDataRobotsResponse.Robot::serialNumber)
                .doesNotContain("GS-ZD-QUIET");
    }

    private Map<String, ZeroDataRobotsResponse.Robot> byId() {
        return service.forMonth(MONTH).robots().stream()
                .collect(Collectors.toMap(ZeroDataRobotsResponse.Robot::serialNumber, Function.identity()));
    }

    private RobotUnit robot(String serial) {
        return robotUnitRepository.save(RobotUnit.builder()
                .serialNumber(serial).brand("Gausium").model("M50").name(serial).build());
    }

    private void deploy(RobotUnit robot, boolean active, LocalDate start, LocalDate end) {
        deploymentRepository.save(Deployment.builder()
                .robotUnit(robot).customerProfile(customer).site("Site Z")
                .isActive(active).deployedAt(LocalDateTime.now())
                .contractStartDate(start).contractEndDate(end).build());
    }

    private void task(RobotUnit robot, LocalDate day, String reportMonth) {
        Instant start = day.atTime(10, 0).atZone(BANGKOK).toInstant();
        taskReportRepository.save(RobotTaskReport.builder()
                .externalTaskId("zd-" + robot.getSerialNumber() + "-" + day)
                .robotUnit(robot)
                .customerProfile(customer)
                .brand("Gausium")
                .reportMonth(reportMonth)
                .startTime(start)
                .endTime(start.plusSeconds(3600))
                .workingTimeSeconds(3600)
                .cleaningAreaSqm(new BigDecimal("100"))
                .plannedAreaSqm(new BigDecimal("100"))
                .syncedAt(Instant.now())
                .taskCompletionPct(new BigDecimal("100"))
                .build());
    }
}
