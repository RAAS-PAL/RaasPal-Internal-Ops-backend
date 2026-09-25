package com.raaspal.robotrecommendation.report;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse;
import com.raaspal.robotrecommendation.report.entity.ReportSend;
import com.raaspal.robotrecommendation.report.repository.ReportSendRepository;
import com.raaspal.robotrecommendation.report.service.ReportDeliveryService;
import com.raaspal.robotrecommendation.report.service.ReportEmailService;
import com.raaspal.robotrecommendation.report.service.ReportEmailService.SentEmail;
import com.raaspal.robotrecommendation.report.service.ReportLinkService;
import com.raaspal.robotrecommendation.report.service.ReportPeriod;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import com.raaspal.robotrecommendation.telemetry.repository.RobotTaskReportRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sharing and emailing a <em>weekly</em> report — the link, the email wording and the
 * send record — alongside the monthly one, which must not change.
 *
 * <p>Week used throughout: <b>2026-W34</b> = Mon 17 – Sun 23 August 2026, inside
 * August, so the month and the week can be compared on the same tasks.
 *
 * <p>{@code management.health.mail.enabled=false} for the same reason as
 * {@link ReportEmailCcTest}: mocking {@code JavaMailSender} otherwise fails the context.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "app.public.base-url=https://example.test",
        "management.health.mail.enabled=false",
})
class WeeklyReportSendTest {

    private static final String SN = "GS-WEEK-SEND-1";
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final ReportPeriod WEEK = ReportPeriod.ofWeek("2026-W34");
    private static final ReportPeriod MONTH = ReportPeriod.ofMonth("2026-08");

    @Autowired private ReportLinkService reportLinkService;
    @Autowired private ReportEmailService reportEmailService;
    @Autowired private ReportDeliveryService reportDeliveryService;
    @Autowired private ReportSendRepository reportSendRepository;
    @Autowired private CustomerProfileRepository customerProfileRepository;
    @Autowired private RobotUnitRepository robotUnitRepository;
    @Autowired private DeploymentRepository deploymentRepository;
    @Autowired private RobotTaskReportRepository taskReportRepository;

    @MockitoBean private JavaMailSender mailSender;

    private CustomerProfile customer;
    private RobotUnit robot;

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        customer = customerProfileRepository.save(CustomerProfile.builder()
                .companyName("Weekly Send Co")
                .contactEmail("ops@customer.test")
                .build());
        robot = robotUnitRepository.save(RobotUnit.builder()
                .serialNumber(SN).brand("Gausium").model("M50").name("Weekly Send").build());
        deploymentRepository.save(Deployment.builder()
                .robotUnit(robot).customerProfile(customer).site("Site A")
                .isActive(true).deployedAt(LocalDateTime.now()).build());

        taskOn(LocalDate.of(2026, 8, 17)); // inside W34
        taskOn(LocalDate.of(2026, 8, 24)); // August, but the week after
    }

    private void taskOn(LocalDate date) {
        Instant start = date.atTime(10, 0).atZone(BANGKOK).toInstant();
        taskReportRepository.save(RobotTaskReport.builder()
                .externalTaskId("wk-send-" + start)
                .robotUnit(robot)
                .customerProfile(customer)
                .brand("Gausium")
                .reportMonth(YearMonth.from(start.atZone(ZoneOffset.UTC)).toString())
                .startTime(start)
                .endTime(start.plusSeconds(3600))
                .workingTimeSeconds(3600)
                .cleaningAreaSqm(new BigDecimal("100"))
                .plannedAreaSqm(new BigDecimal("100"))
                .syncedAt(Instant.now())
                .taskCompletionPct(new BigDecimal("100"))
                .build());
    }

    private MimeMessage sentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    private static String tokenOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /* ─── Links ──────────────────────────────────────────────────────────── */

    /** Re-sharing the same week returns the same URL; the month has its own. */
    @Test
    void aWeeklyLinkIsStableAndSeparateFromTheMonthlyOne() {
        String weekly = reportLinkService.createOrGetToken(SN, WEEK);

        assertThat(reportLinkService.createOrGetToken(SN, WEEK))
                .as("one stable link per robot per week")
                .isEqualTo(weekly);
        assertThat(reportLinkService.createOrGetToken(SN, MONTH))
                .as("the week and the month it sits in are different reports")
                .isNotEqualTo(weekly);
    }

    /** What the customer sees when they open the link — the week, not the month. */
    @Test
    void aWeeklyLinkOpensTheWeeklyReport() {
        ReportPreviewResponse weekly = reportLinkService.resolve(reportLinkService.createOrGetToken(SN, WEEK));
        ReportPreviewResponse monthly = reportLinkService.resolve(reportLinkService.createOrGetToken(SN, MONTH));

        assertThat(weekly.periodLabel()).isEqualTo("17 – 23 August 2026");
        assertThat(weekly.executive().totalTasksCompleted()).isEqualTo(1);
        assertThat(monthly.periodLabel()).isEqualTo("August 2026");
        assertThat(monthly.executive().totalTasksCompleted()).isEqualTo(2);
    }

    /* ─── Email ──────────────────────────────────────────────────────────── */

    /** "ประจำสัปดาห์" = "for the week of"; the email links to the weekly report. */
    @Test
    void theWeeklyEmailNamesTheWeekAndLinksToIt() throws Exception {
        SentEmail sent = reportEmailService.send(SN, WEEK);

        assertThat(sentMessage().getSubject())
                .isEqualTo("รายงานสรุปผลการใช้งานหุ่นยนต์ ประจำสัปดาห์ 17 – 23 August 2026");
        assertThat(reportLinkService.resolve(tokenOf(sent.url())).periodLabel())
                .as("the link in a weekly email opens the weekly report")
                .isEqualTo("17 – 23 August 2026");
    }

    /** The monthly email reads exactly as it did before weekly sending existed. */
    @Test
    void theMonthlyEmailWordingIsUnchanged() throws Exception {
        reportEmailService.send(SN, MONTH);

        assertThat(sentMessage().getSubject())
                .isEqualTo("รายงานสรุปผลการใช้งานหุ่นยนต์ ประจำเดือน August 2026");
    }

    /**
     * A weekly send lands in Delivery history under its own week key — which needs
     * the widened {@code report_month} (V59) — and is never mistaken for the month's.
     */
    @Test
    void aWeeklySendIsRecordedUnderTheWeekKey() {
        reportDeliveryService.sendRobotReport(SN, WEEK);

        List<ReportSend> sends = reportSendRepository.findByCustomerProfileIdOrderBySentAtDesc(customer.getId());
        assertThat(sends).hasSize(1);
        assertThat(sends.get(0).getReportMonth()).isEqualTo("2026-W34");
        assertThat(sends.get(0).getKind()).isEqualTo(ReportSend.Kind.ROBOT_REPORT);
        assertThat(sends.get(0).getStatus()).isEqualTo(ReportSend.Status.SENT);
        assertThat(reportSendRepository.findByReportMonthOrderBySentAtDesc("2026-08"))
                .as("a weekly send is not part of the month's history")
                .isEmpty();
    }

    /* ─── Request handling ───────────────────────────────────────────────── */

    /** Link, email and preview all take exactly one of month or week. */
    @Test
    void anEndpointTakesExactlyOneOfMonthOrWeek() {
        assertThat(ReportPeriod.fromRequest(null, "2026-W34").type()).isEqualTo(ReportPeriod.Type.WEEK);
        assertThat(ReportPeriod.fromRequest("2026-08", "").type()).isEqualTo(ReportPeriod.Type.MONTH);

        assertThatThrownBy(() -> ReportPeriod.fromRequest(null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ReportPeriod.fromRequest("2026-08", "2026-W34")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ReportPeriod.fromRequest(null, "2025-W53"))
                .as("a week that does not exist never becomes a stored link")
                .isInstanceOf(BadRequestException.class);
    }

    /** A stored key reads back as the kind of period it was saved as. */
    @Test
    void aStoredKeyReadsBackAsItsKind() {
        assertThat(ReportPeriod.parse("2026-W34")).isEqualTo(WEEK);
        assertThat(ReportPeriod.parse("2026-08")).isEqualTo(MONTH);
    }
}
