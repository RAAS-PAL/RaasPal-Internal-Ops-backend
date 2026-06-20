package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.report.core.ReportGenerator;
import com.raaspal.robotrecommendation.report.core.ReportGeneratorRegistry;
import com.raaspal.robotrecommendation.report.delivery.MonthlyReportPayload;
import com.raaspal.robotrecommendation.report.delivery.N8nReportClient;
import com.raaspal.robotrecommendation.report.storage.SupabaseStorageService;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import com.raaspal.robotrecommendation.telemetry.repository.RobotTaskReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonthlyReportServiceTest {

    private static final String MONTH = "2026-05";

    @Mock private RobotTaskReportRepository taskReportRepository;
    @Mock private ReportGeneratorRegistry generatorRegistry;
    @Mock private SupabaseStorageService storageService;
    @Mock private N8nReportClient n8nReportClient;
    @Mock private ReportGenerator gausiumGenerator;

    @InjectMocks private MonthlyReportService service;

    @BeforeEach
    void configured() {
        when(storageService.isConfigured()).thenReturn(true);
    }

    @Test
    void testModeUploadsEveryRobotButSendsNothing() throws IOException {
        // Customer A: 2 robots; Customer B: 1 robot
        CustomerProfile a = customer("Customer A", "Uline-A");
        CustomerProfile b = customer("Customer B", "Uline-B");
        RobotUnit a1 = robot("A1");
        RobotUnit a2 = robot("A2");
        RobotUnit b1 = robot("B1");

        when(taskReportRepository.findByReportMonthWithRefs(MONTH)).thenReturn(List.of(
                report(a, a1), report(a, a1), report(a, a2), report(b, b1)));
        when(generatorRegistry.getGenerator(anyString())).thenReturn(gausiumGenerator);
        when(gausiumGenerator.generate(any())).thenReturn(new byte[]{1, 2, 3});
        when(storageService.uploadAndSign(anyString(), any())).thenReturn("https://signed.example/url");

        MonthlyReportSummary summary = service.generateAndSend(MONTH, true);

        // one upload per robot (3 robots), zero LINE/n8n sends
        verify(storageService, times(3)).uploadAndSign(anyString(), any());
        verifyNoInteractions(n8nReportClient);

        assertThat(summary.testMode()).isTrue();
        assertThat(summary.customersProcessed()).isEqualTo(2);
        assertThat(summary.robotsReported()).isEqualTo(3);
        assertThat(summary.messagesSent()).isZero();
        // one preview per customer, A bundling 2 robot links, B bundling 1
        assertThat(summary.previews()).hasSize(2);
        MonthlyReportPayload payloadA = summary.previews().stream()
                .filter(p -> p.customerName().equals("Customer A")).findFirst().orElseThrow();
        assertThat(payloadA.robots()).hasSize(2);
        assertThat(payloadA.lineUserId()).isEqualTo("Uline-A");
    }

    @Test
    void liveModeSendsOnePayloadPerCustomerAndSkipsMissingRecipient() throws IOException {
        CustomerProfile withLine = customer("Has LINE", "U-line");
        CustomerProfile noLine = customer("No LINE", null);
        RobotUnit r1 = robot("R1");
        RobotUnit r2 = robot("R2");

        when(taskReportRepository.findByReportMonthWithRefs(MONTH)).thenReturn(List.of(
                report(withLine, r1), report(noLine, r2)));
        when(n8nReportClient.isConfigured()).thenReturn(true);
        when(generatorRegistry.getGenerator(anyString())).thenReturn(gausiumGenerator);
        when(gausiumGenerator.generate(any())).thenReturn(new byte[]{9});
        when(storageService.uploadAndSign(anyString(), any())).thenReturn("https://signed.example/url");

        MonthlyReportSummary summary = service.generateAndSend(MONTH, false);

        ArgumentCaptor<MonthlyReportPayload> sent = ArgumentCaptor.forClass(MonthlyReportPayload.class);
        verify(n8nReportClient, times(1)).send(sent.capture());
        assertThat(sent.getValue().customerName()).isEqualTo("Has LINE");

        assertThat(summary.testMode()).isFalse();
        assertThat(summary.messagesSent()).isEqualTo(1);
        assertThat(summary.recipientsSkipped()).isEqualTo(1);  // the no-LINE customer
        assertThat(summary.robotsReported()).isEqualTo(2);     // both files still generated
    }

    @Test
    void uploadsUseCustomerMonthSerialObjectPath() throws IOException {
        CustomerProfile a = customer("Customer A", "U-line");
        RobotUnit a1 = robot("SERIAL-XYZ");

        when(taskReportRepository.findByReportMonthWithRefs(MONTH)).thenReturn(List.of(report(a, a1)));
        when(generatorRegistry.getGenerator(anyString())).thenReturn(gausiumGenerator);
        when(gausiumGenerator.generate(any())).thenReturn(new byte[]{1});
        when(storageService.uploadAndSign(anyString(), any())).thenReturn("https://signed.example/url");

        service.generateAndSend(MONTH, true);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(storageService).uploadAndSign(path.capture(), any());
        assertThat(path.getValue()).isEqualTo(a.getId() + "/2026-05/SERIAL-XYZ.xlsx");
    }

    @Test
    void weeklyResolvesIsoWeekRangeAndLabelsObjectPathByWeek() throws IOException {
        CustomerProfile a = customer("Customer A", "U-line");
        RobotUnit a1 = robot("SERIAL-XYZ");

        // 2026-06-17 is a Wednesday → ISO week 2026-W25, Monday 2026-06-15 .. next Monday 2026-06-22.
        when(taskReportRepository.findByStartTimeBetweenWithRefs(any(), any()))
                .thenReturn(List.of(report(a, a1)));
        when(generatorRegistry.getGenerator(anyString())).thenReturn(gausiumGenerator);
        when(gausiumGenerator.generate(any())).thenReturn(new byte[]{1});
        when(storageService.uploadAndSign(anyString(), any())).thenReturn("https://signed.example/url");

        MonthlyReportSummary summary = service.generateAndSendWeekly("2026-06-17", true);

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(taskReportRepository).findByStartTimeBetweenWithRefs(start.capture(), end.capture());
        assertThat(start.getValue()).isEqualTo(Instant.parse("2026-06-15T00:00:00Z"));
        assertThat(end.getValue()).isEqualTo(Instant.parse("2026-06-22T00:00:00Z"));

        assertThat(summary.reportMonth()).isEqualTo("2026-W25");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(storageService).uploadAndSign(path.capture(), any());
        assertThat(path.getValue()).isEqualTo(a.getId() + "/2026-W25/SERIAL-XYZ.xlsx");
        verifyNoInteractions(n8nReportClient);
    }

    @Test
    void emptyMonthReturnsEmptySummaryAndTouchesNothing() {
        when(taskReportRepository.findByReportMonthWithRefs(MONTH)).thenReturn(List.of());

        MonthlyReportSummary summary = service.generateAndSend(MONTH, true);

        assertThat(summary.customersProcessed()).isZero();
        assertThat(summary.robotsReported()).isZero();
        assertThat(summary.previews()).isEmpty();
        verifyNoInteractions(generatorRegistry, n8nReportClient);
    }

    // ── fixtures ──

    private static CustomerProfile customer(String name, String lineUserId) {
        CustomerProfile c = CustomerProfile.builder()
                .companyName(name)
                .lineUserId(lineUserId)
                .build();
        c.setId(UUID.randomUUID());
        return c;
    }

    private static RobotUnit robot(String serial) {
        RobotUnit r = RobotUnit.builder()
                .serialNumber(serial)
                .brand("GAUSIUM")
                .name("Robot " + serial)
                .build();
        r.setId(UUID.randomUUID());
        return r;
    }

    private static RobotTaskReport report(CustomerProfile customer, RobotUnit robot) {
        return RobotTaskReport.builder()
                .customerProfile(customer)
                .robotUnit(robot)
                .brand("GAUSIUM")
                .reportMonth(MONTH)
                .build();
    }
}
