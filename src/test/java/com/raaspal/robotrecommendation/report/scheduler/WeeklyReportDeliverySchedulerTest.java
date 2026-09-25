package com.raaspal.robotrecommendation.report.scheduler;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Which week the Monday run sends: always the Monday-to-Sunday week that just ended. */
class WeeklyReportDeliverySchedulerTest {

    /** Mon 28 September 2026 sends 21–27 September. */
    @Test
    void onAMondayItSendsTheWeekThatEndedYesterday() {
        assertThat(WeeklyReportDeliveryScheduler.previousWeek(LocalDate.of(2026, 9, 28))).isEqualTo("2026-W39");
    }

    /**
     * Across new year the week belongs to the ISO week-based year, not the calendar
     * year: Mon 4 January 2027 sends 28 December 2026 – 3 January 2027, which is
     * 2026-W53. "2027-W53" does not exist.
     */
    @Test
    void acrossNewYearItUsesTheIsoWeekBasedYear() {
        assertThat(WeeklyReportDeliveryScheduler.previousWeek(LocalDate.of(2027, 1, 4))).isEqualTo("2026-W53");
    }

    /** A run fired late on a Tuesday still sends last week, not the one in progress. */
    @Test
    void aLateRunStillSendsTheFinishedWeek() {
        assertThat(WeeklyReportDeliveryScheduler.previousWeek(LocalDate.of(2026, 9, 29))).isEqualTo("2026-W39");
    }
}
