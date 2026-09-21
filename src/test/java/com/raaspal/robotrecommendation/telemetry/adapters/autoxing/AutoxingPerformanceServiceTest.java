package com.raaspal.robotrecommendation.telemetry.adapters.autoxing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.AutoxingPerformanceService.TaskRow;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingPerformanceReport;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingPerformanceReport.Recommendation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AutoxingPerformanceServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    private static long bkk(int day, int hour) {
        return LocalDateTime.of(2026, 8, day, hour, 15)
                .atZone(AutoxingPerformanceService.BANGKOK).toInstant().toEpochMilli();
    }

    private static TaskRow task(int day, int hour, int runType, boolean cancel, boolean failed, String reason) {
        return new TaskRow("t" + day + hour + runType, bkk(day, hour), 4, runType, 1, true, cancel, failed, reason);
    }

    private static JsonNode stats() throws Exception {
        return JSON.readTree("""
                {"allStatis":[
                  {"date":"2026-08-03","count":4,"duration":1200000,"mileage":2500.0},
                  {"date":"2026-08-04","count":2,"duration":600000,"mileage":1500.0}]}""");
    }

    @Test
    void flagsOmittedByAutoxingReadAsFalse() throws Exception {
        TaskRow row = TaskRow.of(JSON.readTree("""
                {"taskId":"a","createTime":1,"taskType":4,"runType":21,"sourceType":1,"isFinish":true}"""));
        assertThat(row.cancelled()).isFalse();
        assertThat(row.failed()).isFalse();
        assertThat(row.outcome()).isEqualTo(AutoxingPerformanceService.Outcome.COMPLETED);

        TaskRow cancelled = TaskRow.of(JSON.readTree("""
                {"taskId":"b","createTime":1,"taskType":4,"runType":21,"isFinish":true,"isCancel":true}"""));
        assertThat(cancelled.outcome()).isEqualTo(AutoxingPerformanceService.Outcome.CANCELLED);
    }

    @Test
    void chargingIsKeptOutOfWorkAndCountedSeparately() throws Exception {
        List<TaskRow> tasks = new ArrayList<>(List.of(
                task(3, 9, 21, false, false, null),
                task(3, 9, 21, false, false, null),
                task(3, 10, 21, true, false, null),
                task(4, 14, 20, false, true, "Path blocked"),
                task(4, 15, 25, false, false, null)));   // charging

        AutoxingPerformanceReport r = AutoxingPerformanceService.assemble("SN1", FROM, TO, tasks, stats(), 4, null,
                "Robot", "Zara", "Customer", "Site", new ArrayList<>());

        assertThat(r.reliability().totalTasks()).isEqualTo(4);
        assertThat(r.reliability().chargingSessions()).isEqualTo(1);
        assertThat(r.summary().tasksCompleted()).isEqualTo(2);
        // completed / (completed + cancelled + failed) = 2 / 4
        assertThat(r.summary().completionRatePct()).isEqualTo(50.0);
        assertThat(r.summary().activeDays()).isEqualTo(2);
        assertThat(r.summary().totalDays()).isEqualTo(31);
        assertThat(r.summary().distanceKm()).isEqualTo(4.0);
        assertThat(r.summary().operatingSeconds()).isEqualTo(1800);
        assertThat(r.summary().tasksChangePct()).isEqualTo(-50.0);
        assertThat(r.operational().avgTaskSeconds()).isEqualTo(300);
        assertThat(r.operational().daily()).hasSize(31);
        assertThat(r.operational().hourly().get(9)).isEqualTo(2);
        assertThat(r.operational().peakHourStart()).isEqualTo(9);
        assertThat(r.operational().taskMix().get(0).key()).isEqualTo("multi_point");
        assertThat(r.operational().sources().get(0).key()).isEqualTo("robot_screen");
        assertThat(r.reliability().topFailureReasons().get(0).reason()).isEqualTo("Path blocked");
        assertThat(r.periodLabel()).isEqualTo("August 2026");

        assertThat(r.recommendations()).extracting(Recommendation::code)
                .contains("LOW_COMPLETION", "HIGH_CANCEL", "FAILURES", "IDLE_DAYS", "PEAK_HOURS", "USAGE_DROP")
                .doesNotContain("HEALTHY", "NO_DATA");
    }

    @Test
    void emptyPeriodSaysNoData() throws Exception {
        AutoxingPerformanceReport r = AutoxingPerformanceService.assemble("SN1", FROM, TO, List.of(),
                JSON.readTree("{}"), null, null, "Robot", null, "—", "—", new ArrayList<>());
        assertThat(r.summary().completionRatePct()).isNull();
        assertThat(r.operational().peakHourStart()).isNull();
        assertThat(r.recommendations()).extracting(Recommendation::code).containsExactly("NO_DATA");
    }
}
