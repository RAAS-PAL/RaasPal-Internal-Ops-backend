package com.raaspal.robotrecommendation.telemetry.adapters.pudu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.telemetry.adapters.pudu.dto.PuduDeliveryReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The report built from PUDU's per-robot-per-day rows. Row shapes are copied from the
 * "Delivery - List Pagination Search" doc's example: {@code mileage} in km,
 * {@code duration} in hours, both to two decimals.
 */
class PuduReportServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 5);

    private final PuduApiClient client = mock(PuduApiClient.class);
    private PuduReportService service;

    @BeforeEach
    void setUp() {
        when(client.isConfigured()).thenReturn(true);
        service = new PuduReportService(client);
    }

    @Test
    void aggregatesTheRobotsRowsAndConvertsUnits() {
        when(client.deliveryRows(anyLong(), anyLong(), anyInt(), any(), eq("robot"), eq("day")))
                .thenReturn(List.of(
                        row("PD9102211844055", "2026-09-01", 0.5, 1.25, 12, 10, 14),
                        row("PD9102211844055", "2026-09-03", 1.0, 2.0, 20, 18, 22),
                        // Another robot in the same store: not ours.
                        row("OP2050E73E6136", "2026-09-02", 9.0, 9.0, 99, 99, 99)))
                .thenReturn(List.of());  // previous period: nothing

        PuduDeliveryReport r = service.build("PD9102211844055", FROM, TO, null, null);

        assertThat(r.robotId()).isEqualTo("PD9102211844055");
        assertThat(r.robotName()).isEqualTo("Bella 1");
        assertThat(r.model()).isEqualTo("bellabot");
        assertThat(r.siteBranch()).isEqualTo("Central Rama 9");
        assertThat(r.customerName()).isEqualTo("Central Rama 9");
        assertThat(r.periodLabel()).isEqualTo("1–5 September 2026");

        PuduDeliveryReport.Summary s = r.summary();
        assertThat(s.totalTasks()).isEqualTo(32);
        assertThat(s.deliveryTasks()).isEqualTo(32);
        assertThat(s.deliverySharePct()).isEqualTo(100.0);
        assertThat(s.totalMileageMeters()).isEqualTo(1500.0);      // 1.5 km
        assertThat(s.totalDurationSeconds()).isEqualTo(11700L);    // 3.25 h
        assertThat(s.activeDays()).isEqualTo(2);
        assertThat(s.totalDays()).isEqualTo(5);
        assertThat(s.tasksPerActiveDay()).isEqualTo(16.0);
        assertThat(s.avgTaskSeconds()).isEqualTo(365L);            // 11700 / 32
        assertThat(s.busiestDate()).isEqualTo("2026-09-03");
        assertThat(s.busiestCount()).isEqualTo(20);

        assertThat(r.pudu().tableCount()).isEqualTo(28);
        assertThat(r.pudu().trayCount()).isEqualTo(36);
        assertThat(r.pudu().avgSpeedMps()).isEqualTo(0.13);        // 1500 / 11700
        assertThat(r.note()).isNull();
    }

    /** Every day of the period is present, idle days at zero, so the chart has a full axis. */
    @Test
    void fillsIdleDaysWithZeros() {
        when(client.deliveryRows(anyLong(), anyLong(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(row("PD1", "2026-09-03", 1.0, 1.0, 5, 5, 5)))
                .thenReturn(List.of());

        PuduDeliveryReport r = service.build("PD1", FROM, TO, null, null);

        assertThat(r.daily()).extracting(PuduDeliveryReport.DailyStat::date)
                .containsExactly("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04", "2026-09-05");
        assertThat(r.daily()).extracting(PuduDeliveryReport.DailyStat::count)
                .containsExactly(0, 0, 5, 0, 0);
        assertThat(r.categories()).hasSize(1);
        assertThat(r.categories().get(0).category()).isEqualTo("delivery");
        assertThat(r.categories().get(0).count()).isEqualTo(5);
    }

    /** The previous period is the same query, same robot, shifted back by the window's length. */
    @Test
    void previousPeriodIsTheSameRobotOverThePrecedingWindow() {
        when(client.deliveryRows(anyLong(), anyLong(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(row("PD1", "2026-09-02", 1.0, 1.0, 10, 8, 9)))
                .thenReturn(List.of(
                        row("PD1", "2026-08-28", 2.0, 3.0, 30, 25, 27),
                        row("OTHER", "2026-08-28", 5.0, 5.0, 50, 50, 50)));

        PuduDeliveryReport r = service.build("PD1", FROM, TO, 331300000L, null);

        PuduDeliveryReport.PreviousPeriod p = r.pudu().previousPeriod();
        assertThat(p.periodLabel()).isEqualTo("27–31 August 2026");
        assertThat(p.totalTasks()).isEqualTo(30);
        assertThat(p.totalMileageMeters()).isEqualTo(2000.0);
        assertThat(p.totalDurationSeconds()).isEqualTo(10800L);
        assertThat(p.tableCount()).isEqualTo(25);
        assertThat(p.trayCount()).isEqualTo(27);

        // Both calls: Bangkok day boundaries as epoch seconds, offset 7, the store passed through.
        ArgumentCaptor<Long> start = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> end = ArgumentCaptor.forClass(Long.class);
        verify(client, org.mockito.Mockito.times(2))
                .deliveryRows(start.capture(), end.capture(), eq(7), eq(331300000L), eq("robot"), eq("day"));
        assertThat(start.getAllValues().get(0)).isEqualTo(FROM.atStartOfDay(PuduReportService.BUSINESS_ZONE).toEpochSecond());
        assertThat(end.getAllValues().get(0)).isEqualTo(TO.plusDays(1).atStartOfDay(PuduReportService.BUSINESS_ZONE).toEpochSecond() - 1);
        assertThat(start.getAllValues().get(1)).isEqualTo(LocalDate.of(2026, 8, 27).atStartOfDay(PuduReportService.BUSINESS_ZONE).toEpochSecond());
    }

    /** An idle or unknown robot is a report with zeros and a note, not an error. */
    @Test
    void noRowsIsAnEmptyReportWithANote() {
        when(client.deliveryRows(anyLong(), anyLong(), anyInt(), any(), any(), any())).thenReturn(List.of());

        PuduDeliveryReport r = service.build("PD-NOBODY", FROM, TO, null, "Customer X");

        assertThat(r.summary().totalTasks()).isZero();
        assertThat(r.summary().busiestDate()).isNull();
        assertThat(r.robotName()).isEqualTo("PUDU robot");
        assertThat(r.customerName()).isEqualTo("Customer X");
        assertThat(r.pudu().avgSpeedMps()).isNull();
        assertThat(r.note()).contains("PD-NOBODY").contains("idle");
    }

    @Test
    void serialMatchIsCaseInsensitiveAndTrimmed() {
        when(client.deliveryRows(anyLong(), anyLong(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(row("pd9102211844055", "2026-09-01", 1.0, 1.0, 3, 3, 3)))
                .thenReturn(List.of());

        PuduDeliveryReport r = service.build("  PD9102211844055 ", FROM, TO, null, null);

        assertThat(r.summary().totalTasks()).isEqualTo(3);
        assertThat(r.robotId()).isEqualTo("PD9102211844055");
    }

    @Test
    void guards() {
        assertThatThrownBy(() -> service.build("PD1", TO, FROM, null, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("start date");
        assertThatThrownBy(() -> service.build("PD1", FROM, FROM.plusDays(31), null, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("31 days");
        assertThatThrownBy(() -> service.build(" ", FROM, TO, null, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("serial");

        when(client.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service.build("PD1", FROM, TO, null, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("not configured");
    }

    /** A rejected key is reported as such, not as a generic failure. */
    @Test
    void anAuthFailureSaysSo() {
        when(client.deliveryRows(anyLong(), anyLong(), anyInt(), any(), any(), any()))
                .thenThrow(new PuduApiException("HTTP 401 {\"message\":\"Invalid API key in request\"}", true));

        assertThatThrownBy(() -> service.build("PD1", FROM, TO, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("rejected the API key");
    }

    private static JsonNode row(String sn, String day, double km, double hours, int tasks, int tables, int trays) {
        return JSON.createObjectNode()
                .put("task_time", day)
                .put("mac", "C0:84:7D:18:A8:4E")
                .put("shop_id", 331300000)
                .put("shop_name", "Central Rama 9")
                .put("product_code", "67")
                .put("product_name", "bellabot")
                .put("robot_name", "Bella 1")
                .put("sn", sn)
                .put("mileage", km)
                .put("duration", hours)
                .put("task_count", tasks)
                .put("table_count", tables)
                .put("tray_count", trays)
                .put("speed", 0.1)
                .put("already_unbind", false);
    }
}
