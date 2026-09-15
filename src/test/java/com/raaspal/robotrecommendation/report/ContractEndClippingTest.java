package com.raaspal.robotrecommendation.report;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse;
import com.raaspal.robotrecommendation.report.service.ReportPeriod;
import com.raaspal.robotrecommendation.report.service.ReportPreviewService;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clipping a monthly report to the deployment's contract end — the mirror of
 * {@link ContractStartClippingTest}.
 *
 * <p>A robot whose contract ended on the 15th must not report the fortnight it spent
 * working for whoever had it next. And a month that begins after the end is not the
 * customer's at all: {@link ReportPeriod#coversContract} says so, and the bundle
 * leaves the robot out.
 */
@SpringBootTest
@Transactional
class ContractEndClippingTest {

    private static final String MONTH = "2026-07";
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    @Autowired private ReportPreviewService reportPreviewService;
    @Autowired private CustomerProfileRepository customerProfileRepository;
    @Autowired private RobotUnitRepository robotUnitRepository;
    @Autowired private DeploymentRepository deploymentRepository;
    @Autowired private RobotTaskReportRepository taskReportRepository;

    private CustomerProfile customer;
    private RobotUnit robot;
    private Deployment deployment;

    @BeforeEach
    void setUp() {
        customer = customerProfileRepository.save(
                CustomerProfile.builder().companyName("End-Of-Contract Co").build());
        robot = robotUnitRepository.save(RobotUnit.builder()
                .serialNumber("GS-END-1").brand("Gausium").model("M50").name("End Test").build());
        deployment = deploymentRepository.save(Deployment.builder()
                .robotUnit(robot).customerProfile(customer).site("Site B")
                .isActive(true).deployedAt(LocalDateTime.now()).build());
    }

    private void taskOn(int dayOfMonth) {
        taskAt(LocalDate.of(2026, 7, dayOfMonth).atTime(10, 0).atZone(BANGKOK).toInstant());
    }

    private void taskAt(Instant startTime) {
        taskReportRepository.save(RobotTaskReport.builder()
                .externalTaskId("t-" + startTime)
                .robotUnit(robot)
                .customerProfile(customer)
                .brand("Gausium")
                .reportMonth(MONTH)
                .startTime(startTime)
                .endTime(startTime.plusSeconds(3600))
                .workingTimeSeconds(3600)
                .cleaningAreaSqm(new BigDecimal("100"))
                .plannedAreaSqm(new BigDecimal("100"))
                .syncedAt(Instant.now())
                .taskCompletionPct(new BigDecimal("100"))
                .build());
    }

    private void setContract(LocalDate start, LocalDate end) {
        deployment.setContractStartDate(start);
        deployment.setContractEndDate(end);
        deploymentRepository.save(deployment);
    }

    private int tasksInReport() {
        ReportPreviewResponse report = reportPreviewService.build("GS-END-1", MONTH);
        return report.executive().totalTasksCompleted();
    }

    /** The default: no end date, the whole month is reported. */
    @Test
    void withNoContractEndTheWholeMonthIsReported() {
        taskOn(5);
        taskOn(25);

        assertThat(tasksInReport()).isEqualTo(2);
    }

    /** The case this feature exists for. */
    @Test
    void tasksAfterTheContractEndAreExcluded() {
        taskOn(5);   // theirs
        taskOn(10);  // theirs
        taskOn(20);  // after the contract
        taskOn(25);  // after
        setContract(null, LocalDate.of(2026, 7, 15));

        assertThat(tasksInReport())
                .as("only work up to the contract end belongs to the customer")
                .isEqualTo(2);
    }

    /** The end date itself is inclusive — the last day of the contract is theirs. */
    @Test
    void aTaskOnTheContractEndDateIsIncluded() {
        taskOn(15);
        setContract(null, LocalDate.of(2026, 7, 15));

        assertThat(tasksInReport()).isEqualTo(1);
    }

    /**
     * The timezone boundary from the other side. 23:00 Bangkok on the end date is
     * 16:00 UTC the same day, still theirs; 01:00 Bangkok the next day is 18:00 UTC on
     * the end date, and is not.
     */
    @Test
    void lateWorkOnTheEndDateIsIncludedAndEarlyWorkTheNextDayIsNot() {
        taskAt(LocalDate.of(2026, 7, 15).atTime(23, 0).atZone(BANGKOK).toInstant());
        taskAt(LocalDate.of(2026, 7, 16).atTime(1, 0).atZone(BANGKOK).toInstant());
        setContract(null, LocalDate.of(2026, 7, 15));

        assertThat(tasksInReport())
                .as("23:00 on the 15th counts; 01:00 on the 16th does not, though both are the 15th in UTC")
                .isEqualTo(1);
    }

    /** Both ends at once: a contract inside the month reports only its own days. */
    @Test
    void startAndEndClipTogether() {
        taskOn(3);   // before
        taskOn(10);  // inside
        taskOn(20);  // inside
        taskOn(28);  // after
        setContract(LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 22));

        assertThat(tasksInReport()).isEqualTo(2);
    }

    /** A contract that ended before the month reports nothing — the robot is not theirs now. */
    @Test
    void aContractThatEndedBeforeTheMonthReportsNothing() {
        taskOn(5);
        taskOn(25);
        setContract(null, LocalDate.of(2026, 6, 30));

        assertThat(tasksInReport()).isZero();
    }

    /** The predicate the bundle uses to leave such a robot out entirely. */
    @Test
    void coversContractSaysWhetherARobotBelongsOnTheMonth() {
        ReportPeriod july = ReportPeriod.ofMonth("2026-07");

        assertThat(july.coversContract(null, null)).as("unbounded").isTrue();
        assertThat(july.coversContract(LocalDate.of(2026, 7, 15), null)).as("starts mid-month").isTrue();
        assertThat(july.coversContract(null, LocalDate.of(2026, 7, 15))).as("ends mid-month").isTrue();
        assertThat(july.coversContract(null, LocalDate.of(2026, 7, 1))).as("ends on day one").isTrue();
        assertThat(july.coversContract(LocalDate.of(2026, 7, 31), null)).as("starts on the last day").isTrue();

        assertThat(july.coversContract(null, LocalDate.of(2026, 6, 30))).as("ended the day before").isFalse();
        assertThat(july.coversContract(LocalDate.of(2026, 8, 1), null)).as("starts the day after").isFalse();
        assertThat(july.coversContract(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 31))).as("long over").isFalse();
    }
}
