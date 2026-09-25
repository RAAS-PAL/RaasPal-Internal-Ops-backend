package com.raaspal.robotrecommendation.report;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.report.entity.ReportSend;
import com.raaspal.robotrecommendation.report.repository.ReportSendRepository;
import com.raaspal.robotrecommendation.report.service.ReportDeliveryService;
import com.raaspal.robotrecommendation.report.service.ReportEmailService;
import com.raaspal.robotrecommendation.report.service.ReportEmailService.SentEmail;
import com.raaspal.robotrecommendation.report.service.ReportPeriod;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A single robot's report sent from the preview tab is part of Delivery history, but
 * it is not the month's bundle. These pin both halves: the row is written, and the
 * monthly run still delivers the bundle to that customer.
 *
 * <p>Email and telemetry are mocked; nothing here reaches SMTP or a brand API.
 */
@SpringBootTest
@Transactional
class RobotReportHistoryTest {

    private static final String MONTH = "2026-08";
    private static final String SERIAL = "GS-HIST-A";

    @Autowired private ReportDeliveryService deliveryService;
    @Autowired private ReportSendRepository reportSends;
    @Autowired private CustomerProfileRepository customerProfiles;
    @Autowired private RobotUnitRepository robotUnits;
    @Autowired private DeploymentRepository deployments;

    @MockitoBean private ReportEmailService emailService;
    @MockitoBean private TelemetrySyncService telemetrySyncService;

    private CustomerProfile customer;

    @BeforeEach
    void setUp() {
        customer = customerProfiles.save(CustomerProfile.builder().companyName("History Test Co").build());
        RobotUnit unit = robotUnits.save(RobotUnit.builder()
                .serialNumber(SERIAL).brand("Gausium").model("M50").name(SERIAL).build());
        deployments.save(Deployment.builder()
                .robotUnit(unit).customerProfile(customer).site("Site A")
                .isActive(true).deployedAt(LocalDateTime.now()).build());

        when(emailService.send(anyString(), any(ReportPeriod.class)))
                .thenReturn(new SentEmail("ops@history.test", "History Test Co", "https://x/report/t"));
        when(emailService.sendBundle(any(), anyString()))
                .thenReturn(new SentEmail("ops@history.test", "History Test Co", "https://x/report/customer/t"));
    }

    private List<ReportSend> rowsFor(UUID customerId) {
        return reportSends.findByCustomerProfileIdOrderBySentAtDesc(customerId);
    }

    @Test
    void aPreviewSendIsRecordedAsARobotReportWithItsSerial() {
        deliveryService.sendRobotReport(SERIAL, ReportPeriod.ofMonth(MONTH));

        List<ReportSend> rows = rowsFor(customer.getId());
        assertThat(rows).hasSize(1);
        ReportSend row = rows.get(0);
        assertThat(row.getKind()).isEqualTo(ReportSend.Kind.ROBOT_REPORT);
        assertThat(row.getRobotSerial()).isEqualTo(SERIAL);
        assertThat(row.getStatus()).isEqualTo(ReportSend.Status.SENT);
        assertThat(row.getRecipientEmail()).isEqualTo("ops@history.test");
        assertThat(row.getReportMonth()).isEqualTo(MONTH);
    }

    /** The whole point: a robot report by hand must not make the run skip the bundle. */
    @Test
    void aRobotReportDoesNotCountAsTheMonthsBundle() {
        deliveryService.sendRobotReport(SERIAL, ReportPeriod.ofMonth(MONTH));

        ReportDeliveryService.RunSummary run = deliveryService.deliverForMonth(MONTH);

        assertThat(run.sent()).isEqualTo(1);
        assertThat(run.skipped()).isZero();
        verify(emailService).sendBundle(eq(customer.getId()), eq(MONTH));
        assertThat(rowsFor(customer.getId()))
                .extracting(ReportSend::getKind)
                .containsExactlyInAnyOrder(ReportSend.Kind.ROBOT_REPORT, ReportSend.Kind.BUNDLE);
    }

    /** Bundle idempotency is unchanged: a second run skips a customer already sent. */
    @Test
    void aSentBundleStillMakesTheNextRunSkip() {
        deliveryService.deliverForMonth(MONTH);
        ReportDeliveryService.RunSummary second = deliveryService.deliverForMonth(MONTH);

        assertThat(second.sent()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
        assertThat(rowsFor(customer.getId())).hasSize(1);
    }

    @Test
    void aFailedPreviewSendIsRecordedAndStillReportedToTheCaller() {
        when(emailService.send(anyString(), any(ReportPeriod.class))).thenThrow(new IllegalStateException("SMTP down"));

        assertThatThrownBy(() -> deliveryService.sendRobotReport(SERIAL, ReportPeriod.ofMonth(MONTH)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SMTP down");

        List<ReportSend> rows = rowsFor(customer.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getKind()).isEqualTo(ReportSend.Kind.ROBOT_REPORT);
        assertThat(rows.get(0).getStatus()).isEqualTo(ReportSend.Status.FAILED);
        assertThat(rows.get(0).getErrorMessage()).isEqualTo("SMTP down");
    }

    /** Nothing was attempted, so there is nothing to record. */
    @Test
    void anUndeployedRobotIsRefusedBeforeAnySendAndLeavesNoRow() {
        robotUnits.save(RobotUnit.builder()
                .serialNumber("GS-HIST-LOOSE").brand("Gausium").model("M50").name("loose").build());

        assertThatThrownBy(() -> deliveryService.sendRobotReport("GS-HIST-LOOSE", ReportPeriod.ofMonth(MONTH)))
                .isInstanceOf(BadRequestException.class);

        verify(emailService, never()).send(anyString(), any(ReportPeriod.class));
        assertThat(reportSends.findByReportMonthOrderBySentAtDesc(MONTH)).isEmpty();
    }

    @Test
    void bundleRowsCarryNoSerial() {
        deliveryService.deliverForMonth(MONTH);

        ReportSend row = rowsFor(customer.getId()).get(0);
        assertThat(row.getKind()).isEqualTo(ReportSend.Kind.BUNDLE);
        assertThat(row.getRobotSerial()).isNull();
    }
}
