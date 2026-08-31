package com.raaspal.robotrecommendation.report;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The weekly performance report: the same aggregation as the monthly one, over an
 * ISO week (Monday-Sunday) instead of a calendar month.
 *
 * <p>The week is why this needs its own query path. A month is read straight off
 * the stored {@code report_month} column, but a week routinely straddles two of
 * them, so it is a {@code start_time} range — and that range is anchored in the
 * business timezone, because cleaning robots run overnight and a task at 23:30
 * Bangkok on Sunday is already Monday in UTC.
 *
 * <p>Weeks used here: <b>2026-W34</b> = Mon 17 - Sun 23 August 2026 (inside one
 * month) and <b>2026-W36</b> = Mon 31 August - Sun 6 September 2026 (spanning two).
 */
@SpringBootTest
@Transactional
class WeeklyReportPeriodTest {

    private static final String SN = "GS-WEEK-1";
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    /** Mon 17 - Sun 23 August 2026. */
    private static final String WEEK_IN_ONE_MONTH = "2026-W34";
    /** Mon 31 August - Sun 6 September 2026. */
    private static final String WEEK_ACROSS_MONTHS = "2026-W36";

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
                CustomerProfile.builder().companyName("Weekly Report Co").build());
        robot = robotUnitRepository.save(RobotUnit.builder()
                .serialNumber(SN).brand("Gausium").model("M50").name("Weekly Test").build());
        deploymentRepository.save(Deployment.builder()
                .robotUnit(robot).customerProfile(customer).site("Site A")
                .isActive(true).deployedAt(LocalDateTime.now()).build());
    }

    /** One task at 10:00 Bangkok on the given date. */
    private void taskOn(LocalDate date) {
        taskAt(date.atTime(10, 0).atZone(BANGKOK).toInstant());
    }

    private void taskAt(Instant startTime) {
        taskReportRepository.save(RobotTaskReport.builder()
                .externalTaskId("wk-" + startTime)
                .robotUnit(robot)
                .customerProfile(customer)
                .brand("Gausium")
                // Set exactly as the sync sets it — from the start time in UTC.
                .reportMonth(YearMonth.from(startTime.atZone(ZoneOffset.UTC)).toString())
                .startTime(startTime)
                .endTime(startTime.plusSeconds(3600))
                .workingTimeSeconds(3600)
                .cleaningAreaSqm(new BigDecimal("100"))
                .plannedAreaSqm(new BigDecimal("100"))
                .syncedAt(Instant.now())
                .taskCompletionPct(new BigDecimal("100"))
                .build());
    }

    private ReportPreviewResponse week(String isoWeek) {
        return reportPreviewService.buildForWeek(SN, isoWeek);
    }

    private static LocalDate aug(int day) {
        return LocalDate.of(2026, 8, day);
    }

    /** The window is exactly seven days — the days either side belong to other weeks. */
    @Test
    void onlyTasksInsideTheMondayToSundayWindowAreCounted() {
        taskOn(aug(16)); // Sunday, the week before
        taskOn(aug(17)); // Monday, first day of W34
        taskOn(aug(23)); // Sunday, last day of W34
        taskOn(aug(24)); // Monday, the week after

        assertThat(week(WEEK_IN_ONE_MONTH).executive().totalTasksCompleted())
                .as("a week is the seven days Mon-Sun, both ends inclusive")
                .isEqualTo(2);
    }

    /**
     * The boundary the timezone choice decides. A task at 23:30 Bangkok on the final
     * Sunday is 16:30 UTC that day — still this week — while 00:30 Bangkok on the
     * following Monday is 17:30 UTC on Sunday, and must not be dragged back in.
     */
    @Test
    void theWeekEndsAtMidnightInTheBusinessTimezoneNotUtc() {
        taskAt(aug(23).atTime(23, 30).atZone(BANGKOK).toInstant());
        taskAt(aug(24).atTime(0, 30).atZone(BANGKOK).toInstant());

        assertThat(week(WEEK_IN_ONE_MONTH).executive().totalTasksCompleted())
                .as("late Sunday night belongs to this week; after midnight to the next")
                .isEqualTo(1);
    }

    /**
     * The case the stored {@code report_month} column cannot serve: a week whose days
     * carry two different report months still reports as one continuous week.
     */
    @Test
    void aWeekSpanningTwoMonthsCountsTasksFromBoth() {
        taskOn(aug(31));                  // report_month 2026-08
        taskOn(LocalDate.of(2026, 9, 2)); // report_month 2026-09
        taskOn(LocalDate.of(2026, 9, 6)); // report_month 2026-09, last day of W36
        taskOn(LocalDate.of(2026, 9, 7)); // Monday, the week after

        assertThat(week(WEEK_ACROSS_MONTHS).executive().totalTasksCompleted())
                .as("a week that straddles a month boundary is still one week")
                .isEqualTo(3);
    }

    /** The customer-facing title names the dates, collapsing whatever the ends share. */
    @Test
    void thePeriodLabelIsTheDateRange() {
        assertThat(week(WEEK_IN_ONE_MONTH).periodLabel())
                .isEqualTo("17 – 23 August 2026");
        assertThat(week(WEEK_ACROSS_MONTHS).periodLabel())
                .as("a week across two months names both")
                .isEqualTo("31 August – 6 September 2026");
    }

    /** No tasks that week is a valid answer, not an error — the report zeroes out. */
    @Test
    void aWeekWithNoTasksReportsZeroRatherThanFailing() {
        taskOn(aug(17));

        ReportPreviewResponse quiet = week("2026-W40");
        assertThat(quiet.executive().totalTasksCompleted()).isZero();
        assertThat(quiet.recommendations())
                .extracting(ReportPreviewResponse.Recommendation::type)
                .containsExactly("noData");
    }

    /**
     * A malformed week is rejected outright. Unlike a month — which is matched
     * against stored values and so simply finds nothing — a week is resolved into a
     * date range, and a typo would otherwise return a confident report for a window
     * nobody asked for.
     */
    @Test
    void aMalformedWeekIsRejected() {
        assertThatThrownBy(() -> week("2025-W53"))
                .as("2025 has only 52 ISO weeks")
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> week("2026-08"))
                .as("a month is not a week")
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> week("not-a-week"))
                .isInstanceOf(BadRequestException.class);
    }

    /** The monthly report still reads the stored report month, labelled as before. */
    @Test
    void theMonthlyReportIsUnchanged() {
        taskOn(aug(17));
        taskOn(aug(24));
        taskOn(LocalDate.of(2026, 9, 2));

        ReportPreviewResponse august = reportPreviewService.build(SN, "2026-08");
        assertThat(august.executive().totalTasksCompleted()).isEqualTo(2);
        assertThat(august.periodLabel()).isEqualTo("August 2026");
    }
}
