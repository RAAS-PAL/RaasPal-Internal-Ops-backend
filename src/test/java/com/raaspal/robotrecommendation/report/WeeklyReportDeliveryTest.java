package com.raaspal.robotrecommendation.report;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.report.dto.CustomerReportBundleResponse;
import com.raaspal.robotrecommendation.report.entity.ReportSend;
import com.raaspal.robotrecommendation.report.repository.ReportSendRepository;
import com.raaspal.robotrecommendation.report.service.CustomerReportBundleService;
import com.raaspal.robotrecommendation.report.service.ReportDeliveryService;
import com.raaspal.robotrecommendation.report.service.ReportDeliveryService.RunSummary;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.ReportCadence;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The automated weekly delivery — the Monday run behind
 * {@code WeeklyReportDeliveryScheduler}.
 *
 * <p>The rules under test, as decided 2026-09-25: a robot set to {@code WEEKLY} is
 * reported weekly <em>instead of</em> monthly; each customer gets one email per week
 * covering their weekly robots only; and a week already sent is never sent again.
 *
 * <p>Week used: <b>2026-W34</b> = Mon 17 – Sun 23 August 2026.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "app.public.base-url=https://example.test",
        "management.health.mail.enabled=false",
})
class WeeklyReportDeliveryTest {

    private static final String WEEK = "2026-W34";
    private static final String MONTH = "2026-08";

    @Autowired private ReportDeliveryService deliveryService;
    @Autowired private CustomerReportBundleService bundleService;
    @Autowired private ReportSendRepository reportSendRepository;
    @Autowired private CustomerProfileRepository customerProfileRepository;
    @Autowired private RobotUnitRepository robotUnitRepository;
    @Autowired private DeploymentRepository deploymentRepository;

    @MockitoBean private JavaMailSender mailSender;
    // The run syncs telemetry before sending; the brand API is not part of this test.
    @MockitoBean private TelemetrySyncService telemetrySyncService;

    private CustomerProfile weeklyCustomer;
    private CustomerProfile monthlyCustomer;

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenAnswer(i -> new JavaMailSenderImpl().createMimeMessage());
        weeklyCustomer = customer("Weekly Co");
        monthlyCustomer = customer("Monthly Co");
        deploy(weeklyCustomer, "GS-AUTO-W1", ReportCadence.WEEKLY);
        deploy(monthlyCustomer, "GS-AUTO-M1", ReportCadence.MONTHLY);
    }

    private CustomerProfile customer(String name) {
        return customerProfileRepository.save(CustomerProfile.builder()
                .companyName(name).contactEmail("ops@" + name.replace(" ", "").toLowerCase() + ".test").build());
    }

    private void deploy(CustomerProfile customer, String serial, ReportCadence cadence) {
        RobotUnit robot = robotUnitRepository.save(RobotUnit.builder()
                .serialNumber(serial).brand("Gausium").model("M50").name(serial).build());
        deploymentRepository.save(Deployment.builder()
                .robotUnit(robot).customerProfile(customer).site("Site")
                .isActive(true).deployedAt(LocalDateTime.now())
                .reportCadence(cadence).build());
    }

    private List<ReportSend> sendsTo(UUID customerId) {
        return reportSendRepository.findByCustomerProfileIdOrderBySentAtDesc(customerId);
    }

    /** Monday's run reaches the weekly customer, once, under the week's key. */
    @Test
    void theWeeklyRunSendsOneBundleToEachWeeklyCustomer() {
        RunSummary summary = deliveryService.deliverForWeek(WEEK);

        assertThat(summary.sent()).isEqualTo(1);
        List<ReportSend> sends = sendsTo(weeklyCustomer.getId());
        assertThat(sends).hasSize(1);
        assertThat(sends.get(0).getReportMonth()).isEqualTo(WEEK);
        assertThat(sends.get(0).getKind()).isEqualTo(ReportSend.Kind.BUNDLE);
        assertThat(sends.get(0).getStatus()).isEqualTo(ReportSend.Status.SENT);
        assertThat(sendsTo(monthlyCustomer.getId()))
                .as("a MONTHLY customer is not part of the weekly run")
                .isEmpty();
    }

    /** The email names the week: ประจำสัปดาห์ = "for the week of". */
    @Test
    void theWeeklyBundleEmailNamesTheWeek() throws Exception {
        deliveryService.deliverForWeek(WEEK);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject())
                .isEqualTo("รายงานสรุปผลการใช้งานหุ่นยนต์ ประจำสัปดาห์ 17 – 23 August 2026");
    }

    /** It syncs the week itself — Monday to Sunday — before sending. */
    @Test
    void theWeeklyRunSyncsExactlyTheWeek() {
        deliveryService.deliverForWeek(WEEK);

        verify(telemetrySyncService).syncBySerialNumber(
                eq("GS-AUTO-W1"), eq(LocalDate.of(2026, 8, 17)), eq(LocalDate.of(2026, 8, 23)));
    }

    /**
     * The scheduler firing after someone already ran the week by hand — or a second
     * click — must not email the customer twice.
     */
    @Test
    void aWeekAlreadySentIsNotSentAgain() {
        deliveryService.deliverForWeek(WEEK);
        RunSummary second = deliveryService.deliverForWeek(WEEK);

        assertThat(second.sent()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    /** Weekly is instead of monthly: a WEEKLY-only customer leaves the monthly run. */
    @Test
    void aWeeklyCustomerIsNotInTheMonthlyRun() {
        deliveryService.deliverForMonth(MONTH);

        assertThat(sendsTo(weeklyCustomer.getId())).isEmpty();
        assertThat(sendsTo(monthlyCustomer.getId()))
                .as("the monthly run is unchanged for everyone else")
                .hasSize(1);
    }

    /**
     * A weekly bundle holds only the robots set to WEEKLY — weekly is opted into robot
     * by robot. The same customer's monthly bundle still holds all of them, as before.
     */
    @Test
    void aWeeklyBundleHoldsOnlyTheWeeklyRobots() {
        deploy(weeklyCustomer, "GS-AUTO-W2-MONTHLY", ReportCadence.MONTHLY);

        CustomerReportBundleResponse weekly = bundleService.build(weeklyCustomer.getId(), WEEK);
        CustomerReportBundleResponse monthly = bundleService.build(weeklyCustomer.getId(), MONTH);

        assertThat(weekly.robots()).extracting(r -> r.serialNumber()).containsExactly("GS-AUTO-W1");
        assertThat(weekly.periodLabel()).isEqualTo("17 – 23 August 2026");
        assertThat(monthly.robots()).hasSize(2);
        assertThat(monthly.periodLabel()).isEqualTo("August 2026");
    }
}
