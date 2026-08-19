package com.raaspal.robotrecommendation.report;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse;
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
 * Clipping a monthly report to the customer's contract start.
 *
 * <p>A customer who signs on the 15th must not be shown the two weeks of work the
 * robot did before they were a customer — the numbers are real, but they are not
 * theirs.
 */
@SpringBootTest
@Transactional
class ContractStartClippingTest {

    private static final String MONTH = "2026-07";
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    @Autowired private ReportPreviewService reportPreviewService;
    @Autowired private CustomerProfileRepository customerProfileRepository;
    @Autowired private RobotUnitRepository robotUnitRepository;
    @Autowired private DeploymentRepository deploymentRepository;
    @Autowired private RobotTaskReportRepository taskReportRepository;

    private CustomerProfile customer;
    private RobotUnit robot;

    @BeforeEach
    void setUp() {
        customer = customerProfileRepository.save(
                CustomerProfile.builder().companyName("Mid-Contract Co").build());
        robot = robotUnitRepository.save(RobotUnit.builder()
                .serialNumber("GS-CLIP-1").brand("Gausium").model("M50").name("Clip Test").build());
        deploymentRepository.save(Deployment.builder()
                .robotUnit(robot).customerProfile(customer).site("Site A")
                .isActive(true).deployedAt(LocalDateTime.now()).build());
    }

    /** One task at 10:00 Bangkok on the given day of July. */
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

    private void setContractStart(LocalDate date) {
        customer.setContractStartDate(date);
        customerProfileRepository.save(customer);
    }

    private int tasksInReport() {
        ReportPreviewResponse report = reportPreviewService.build("GS-CLIP-1", MONTH);
        return report.executive().totalTasksCompleted();
    }

    /** The default for every existing customer: no start date, nothing changes. */
    @Test
    void withNoContractStartTheWholeMonthIsReported() {
        taskOn(5);
        taskOn(20);

        assertThat(tasksInReport()).isEqualTo(2);
    }

    /** The case this feature exists for. */
    @Test
    void tasksBeforeTheContractStartAreExcluded() {
        taskOn(5);   // before they were a customer
        taskOn(10);  // before
        taskOn(20);  // theirs
        taskOn(25);  // theirs
        setContractStart(LocalDate.of(2026, 7, 15));

        assertThat(tasksInReport())
                .as("only work from the contract start onwards belongs to the customer")
                .isEqualTo(2);
    }

    /** The start date itself is inclusive — day one of the contract is theirs. */
    @Test
    void aTaskOnTheContractStartDateIsIncluded() {
        taskOn(15);
        setContractStart(LocalDate.of(2026, 7, 15));

        assertThat(tasksInReport()).isEqualTo(1);
    }

    /**
     * The boundary that makes the timezone choice matter. Cleaning robots run
     * overnight, so a task at 02:00 Bangkok on the start date is 19:00 UTC the
     * previous day. It is the customer's work and must be counted.
     */
    @Test
    void anEarlyMorningTaskOnTheStartDateIsIncludedDespiteBeingThePreviousDayInUtc() {
        taskAt(LocalDate.of(2026, 7, 15).atTime(2, 0).atZone(BANGKOK).toInstant());
        setContractStart(LocalDate.of(2026, 7, 15));

        assertThat(tasksInReport())
                .as("02:00 Bangkok on the 15th is the customer's, though it is the 14th in UTC")
                .isEqualTo(1);
    }

    /** Conversely, late work the night before the contract is not theirs. */
    @Test
    void aTaskLateOnTheDayBeforeTheContractStartIsExcluded() {
        taskAt(LocalDate.of(2026, 7, 14).atTime(23, 0).atZone(BANGKOK).toInstant());
        setContractStart(LocalDate.of(2026, 7, 15));

        assertThat(tasksInReport()).isZero();
    }

    /** From the second month onwards the contract start is behind us — a no-op. */
    @Test
    void aContractStartBeforeTheMonthClipsNothing() {
        taskOn(5);
        taskOn(20);
        setContractStart(LocalDate.of(2026, 6, 10));

        assertThat(tasksInReport()).isEqualTo(2);
    }

    /** A start date after the month means the customer had no contract yet. */
    @Test
    void aContractStartAfterTheMonthReportsNothing() {
        taskOn(5);
        taskOn(20);
        setContractStart(LocalDate.of(2026, 8, 1));

        assertThat(tasksInReport()).isZero();
    }
}
