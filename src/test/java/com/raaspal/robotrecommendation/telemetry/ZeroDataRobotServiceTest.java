package com.raaspal.robotrecommendation.telemetry;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import com.raaspal.robotrecommendation.telemetry.core.ZeroDataRobotService;
import com.raaspal.robotrecommendation.telemetry.dto.ZeroDataRobotsResponse;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which robots the zero-data list names for a month, and which it leaves alone.
 *
 * <p>Five robots, one month (July 2026): one that worked, one that went quiet in June,
 * one that has never synced anything, one whose contract ended in May, and one that is
 * deactivated. Only the quiet one and the never-synced one belong on the list.
 */
@SpringBootTest
@Transactional
class ZeroDataRobotServiceTest {

    private static final String MONTH = "2026-07";
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    @Autowired private ZeroDataRobotService service;
    @Autowired private CustomerProfileRepository customerProfileRepository;
    @Autowired private RobotUnitRepository robotUnitRepository;
    @Autowired private DeploymentRepository deploymentRepository;
    @Autowired private RobotTaskReportRepository taskReportRepository;

    private CustomerProfile customer;

    @BeforeEach
    void setUp() {
        customer = customerProfileRepository.save(
                CustomerProfile.builder().companyName("Quiet Fleet Co").build());

        RobotUnit worked = robot("GS-ZD-WORKED");
        deploy(worked, true, null, null);
        task(worked, LocalDate.of(2026, 7, 10), "2026-07");

        RobotUnit quiet = robot("GS-ZD-QUIET");
        deploy(quiet, true, null, null);
        task(quiet, LocalDate.of(2026, 6, 14), "2026-06");   // last seen in June

        RobotUnit never = robot("GS-ZD-NEVER");
        deploy(never, true, LocalDate.of(2026, 7, 1), null);

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
                .containsExactlyInAnyOrder("GS-ZD-QUIET", "GS-ZD-NEVER");
        assertThat(r.zeroData()).isEqualTo(2);
    }

    /** Worked, ended-before-the-month and deactivated are all left alone, for different reasons. */
    @Test
    void scopeCountsOnlyRobotsUnderContractThatMonth() {
        ZeroDataRobotsResponse r = service.forMonth(MONTH);

        // worked + quiet + never; not the one whose contract ended in May, not the inactive one.
        assertThat(r.inScope()).isEqualTo(3);
        assertThat(r.robots()).extracting(ZeroDataRobotsResponse.Robot::serialNumber)
                .doesNotContain("GS-ZD-WORKED", "GS-ZD-ENDED", "GS-ZD-INACTIVE");
    }

    /** The diagnostic: when it last logged anything, and whether it ever has. */
    @Test
    void saysWhenEachRobotLastLoggedAnything() {
        List<ZeroDataRobotsResponse.Robot> robots = service.forMonth(MONTH).robots();

        ZeroDataRobotsResponse.Robot quiet = robots.stream()
                .filter(x -> x.serialNumber().equals("GS-ZD-QUIET")).findFirst().orElseThrow();
        assertThat(quiet.lastDataDate()).isEqualTo(LocalDate.of(2026, 6, 14));
        assertThat(quiet.daysSinceLastData()).isNotNull().isPositive();
        assertThat(quiet.reason()).isEqualTo("No tasks this month");

        ZeroDataRobotsResponse.Robot never = robots.stream()
                .filter(x -> x.serialNumber().equals("GS-ZD-NEVER")).findFirst().orElseThrow();
        assertThat(never.lastDataDate()).isNull();
        assertThat(never.daysSinceLastData()).isNull();
        assertThat(never.reason()).isEqualTo("Never synced any task");
        assertThat(never.contractStartDate()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    /** Never-synced robots come first — the likelier registration mistakes. */
    @Test
    void neverSyncedRobotsAreListedFirst() {
        List<ZeroDataRobotsResponse.Robot> robots = service.forMonth(MONTH).robots();

        assertThat(robots.get(0).serialNumber()).isEqualTo("GS-ZD-NEVER");
        assertThat(robots.get(1).serialNumber()).isEqualTo("GS-ZD-QUIET");
    }

    /** The robot that went quiet in June was fine in June. */
    @Test
    void theSameRobotIsNotOnTheListForAMonthItWorked() {
        assertThat(service.forMonth("2026-06").robots())
                .extracting(ZeroDataRobotsResponse.Robot::serialNumber)
                .doesNotContain("GS-ZD-QUIET");
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
