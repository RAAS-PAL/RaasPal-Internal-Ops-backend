package com.raaspal.robotrecommendation.report;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.report.entity.ReportSend;
import com.raaspal.robotrecommendation.report.repository.ReportSendRepository;
import com.raaspal.robotrecommendation.report.service.ReportDeliveryService;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.ReportCadence;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The delivery run as the schedulers call it: on a thread with no open session.
 *
 * <p>{@link WeeklyReportDeliveryTest} is {@code @Transactional}, which keeps a session open
 * for the whole test and lets a lazy association load wherever it is touched. The
 * scheduler's thread has no such session, and on 2026-09-28 the first weekly run died on
 * the first lazy robot it touched — before sending or recording anything, so the dashboard
 * showed nothing wrong. This class commits its rows and calls the run outside any
 * transaction, which is the only way to see that failure. It is therefore also the one
 * test that must clean up after itself: the in-memory database outlives it.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.public.base-url=https://example.test",
        "management.health.mail.enabled=false",
})
class ReportDeliveryWithoutSessionTest {

    /** Mon 24 – Sun 30 August 2026; not a week any other test sends. */
    private static final String WEEK = "2026-W35";

    @Autowired private ReportDeliveryService deliveryService;
    @Autowired private ReportSendRepository reportSendRepository;
    @Autowired private CustomerProfileRepository customerProfileRepository;
    @Autowired private RobotUnitRepository robotUnitRepository;
    @Autowired private DeploymentRepository deploymentRepository;

    @MockitoBean private JavaMailSender mailSender;
    @MockitoBean private TelemetrySyncService telemetrySyncService;

    private CustomerProfile customer;
    private RobotUnit robot;
    private Deployment deployment;

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenAnswer(i -> new JavaMailSenderImpl().createMimeMessage());
        customer = customerProfileRepository.save(CustomerProfile.builder()
                .companyName("Sessionless Co").contactEmail("ops@sessionless.test").build());
        robot = robotUnitRepository.save(RobotUnit.builder()
                .serialNumber("GS-NOSESSION-1").brand("Gausium").model("M50").name("GS-NOSESSION-1").build());
        deployment = deploymentRepository.save(Deployment.builder()
                .robotUnit(robot).customerProfile(customer).site("Site")
                .isActive(true).deployedAt(LocalDateTime.now())
                .reportCadence(ReportCadence.WEEKLY).build());
    }

    @AfterEach
    void cleanUp() {
        reportSendRepository.deleteAll(reportSendRepository.findByCustomerProfileIdOrderBySentAtDesc(customer.getId()));
        deploymentRepository.delete(deployment);
        robotUnitRepository.delete(robot);
        customerProfileRepository.delete(customer);
    }

    @Test
    void theWeeklyRunSendsWithNoSessionOpen() {
        deliveryService.deliverForWeek(WEEK);

        List<ReportSend> sends = reportSendRepository.findByCustomerProfileIdOrderBySentAtDesc(customer.getId());
        assertThat(sends).singleElement().satisfies(send -> {
            assertThat(send.getReportMonth()).isEqualTo(WEEK);
            assertThat(send.getStatus()).isEqualTo(ReportSend.Status.SENT);
        });
    }
}
