package com.raaspal.robotrecommendation.kpi;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.CmCounts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.InstallCounts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.MonthMetrics;
import com.raaspal.robotrecommendation.kpi.entity.KpiCaseTicket;
import com.raaspal.robotrecommendation.kpi.entity.ServiceLine;
import com.raaspal.robotrecommendation.kpi.entity.TicketType;
import com.raaspal.robotrecommendation.kpi.repository.KpiCaseTicketRepository;
import com.raaspal.robotrecommendation.kpi.service.KpiCaseMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The board-facing maths, against the RE team's formulas (2026-09-08):
 * 30-day first-time-install, 14-day first-time-fix, 7-day SLA measured from the
 * report to the RE Action date. Every rule has a ticket on each side of it.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:kpi-test.properties")
@Transactional
class KpiCaseMetricsServiceTest {

    private static final String CLEANING_BOARD = "3451717331";
    private static final String DELIVERY_BOARD = "1647612496";
    private static final String INSTALL_BOARD = "9900001111";
    private static final YearMonth JAN = YearMonth.of(2026, 1);
    private static final YearMonth MAR = YearMonth.of(2026, 3);

    @Autowired private KpiCaseMetricsService service;
    @Autowired private KpiCaseTicketRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    /** A CM case: reported on {@code open}, first acted on {@code action} (nullable). */
    private KpiCaseTicket cm(ServiceLine line, String id, LocalDate open, LocalDate action, String serial) {
        return cm(line, id, open, action, serial, "Incident case");
    }

    private KpiCaseTicket cm(ServiceLine line, String id, LocalDate open, LocalDate action, String serial, String category) {
        return save(KpiCaseTicket.builder()
                .sourceBoardId(line == ServiceLine.CLEANING ? CLEANING_BOARD : DELIVERY_BOARD)
                .sourceItemId(id)
                .serviceLine(line)
                .ticketType(TicketType.CM)
                .openDate(open)
                .actionDate(action)
                .serialsNormalised(serial)
                .category(category));
    }

    /**
     * An installation finishing on {@code installDate}. Its service line is left
     * null, like the real board, so it can only be classified by serial.
     */
    private KpiCaseTicket install(String id, LocalDate installDate, String serial) {
        return install(id, installDate, serial, "Installation");
    }

    private KpiCaseTicket install(String id, LocalDate installDate, String serial, String jobType) {
        return save(KpiCaseTicket.builder()
                .sourceBoardId(INSTALL_BOARD)
                .sourceItemId(id)
                .ticketType(TicketType.INSTALLATION)
                .installDate(installDate)
                .serialsNormalised(serial)
                .category(jobType));
    }

    private KpiCaseTicket save(KpiCaseTicket.KpiCaseTicketBuilder builder) {
        return repository.save(builder
                .source(KpiCaseTicket.SOURCE_MONDAY)
                .firstSeenAt(LocalDateTime.of(2026, 9, 1, 0, 0))
                .lastSyncedAt(LocalDateTime.of(2026, 9, 8, 1, 30))
                .present(true)
                .build());
    }

    private static MonthMetrics month(KpiCaseMetricsResponse r, String m) {
        return r.months().stream().filter(x -> x.month().equals(m)).findFirst().orElseThrow();
    }

    // ── 1st Time Install ────────────────────────────────────────────────────

    /** A CM on the same serial inside 30 days of the install finishing scores it 0. */
    @Test
    void installFollowedByCmWithinThirtyDaysScoresZero() {
        install("I1", d(1, 5), "S1");
        cm(ServiceLine.CLEANING, "C1", d(2, 3), null, "S1");   // 29 days later

        InstallCounts jan = month(service.monthly(JAN, MAR), "2026-01").cleaning().installation();

        assertThat(jan.total()).isEqualTo(1);
        assertThat(jan.followedByCm()).isEqualTo(1);
        assertThat(jan.firstTime()).isZero();
        assertThat(jan.firstTimeRate()).isEqualTo(0.0);
    }

    /** Exactly 30 days still counts against it; 31 does not. */
    @Test
    void thirtyDayBoundaryIsInclusive() {
        install("I1", d(1, 1), "S1");
        cm(ServiceLine.CLEANING, "C1", d(1, 31), null, "S1");      // exactly 30
        install("I2", d(1, 1), "S2");
        cm(ServiceLine.CLEANING, "C2", d(2, 1), null, "S2");       // 31

        InstallCounts jan = month(service.monthly(JAN, MAR), "2026-01").cleaning().installation();

        assertThat(jan.total()).isEqualTo(2);
        assertThat(jan.followedByCm()).isEqualTo(1);
        assertThat(jan.firstTime()).isEqualTo(1);
        assertThat(jan.firstTimeRate()).isEqualTo(50.0);
    }

    /** A CM for a different robot is irrelevant, however close in time. */
    @Test
    void installIsOnlyJudgedByItsOwnSerial() {
        install("I1", d(1, 5), "S1");
        cm(ServiceLine.CLEANING, "C1", d(1, 6), null, "S-OTHER");

        assertThat(month(service.monthly(JAN, JAN), "2026-01").all().installation().firstTime()).isEqualTo(1);
    }

    /** Installations are counted in the month the TimeLine ends, not when it started. */
    @Test
    void installationsAreBucketedByTimelineEnd() {
        install("I1", d(2, 14), "S1");
        cm(ServiceLine.DELIVERY, "C-known", d(2, 20), null, "S1");   // identifies the robot

        KpiCaseMetricsResponse r = service.monthly(JAN, MAR);

        assertThat(month(r, "2026-01").all().installation().total()).isZero();
        assertThat(month(r, "2026-02").delivery().installation().total()).isEqualTo(1);
        assertThat(month(r, "2026-02").cleaning().installation().total()).isZero();
        assertThat(r.unclassifiedTickets()).isZero();
    }

    // ── First Time Fix ──────────────────────────────────────────────────────

    /** A second CM on the same serial inside 14 days scores the FIRST one 0. */
    @Test
    void cmFollowedWithinFourteenDaysScoresZero() {
        cm(ServiceLine.CLEANING, "C1", d(1, 5), null, "S1");
        cm(ServiceLine.CLEANING, "C2", d(1, 15), null, "S1");   // 10 days later

        CmCounts jan = month(service.monthly(JAN, MAR), "2026-01").cleaning().cm();

        assertThat(jan.total()).isEqualTo(2);
        assertThat(jan.repeat()).isEqualTo(1);        // C1 failed
        assertThat(jan.firstTimeFix()).isEqualTo(1);  // C2 has no follower
        assertThat(jan.firstTimeFixRate()).isEqualTo(50.0);
    }

    @Test
    void fourteenDayBoundaryIsInclusive() {
        cm(ServiceLine.CLEANING, "C1", d(1, 1), null, "S1");
        cm(ServiceLine.CLEANING, "C2", d(1, 15), null, "S1");   // exactly 14
        cm(ServiceLine.CLEANING, "C3", d(2, 1), null, "S2");
        cm(ServiceLine.CLEANING, "C4", d(2, 16), null, "S2");   // 15

        KpiCaseMetricsResponse r = service.monthly(JAN, MAR);

        assertThat(month(r, "2026-01").cleaning().cm().repeat()).isEqualTo(1);
        assertThat(month(r, "2026-02").cleaning().cm().repeat()).isZero();
    }

    /** A repeat that lands in the next month still condemns the earlier ticket. */
    @Test
    void followerOutsideTheRangeStillCounts() {
        cm(ServiceLine.CLEANING, "C1", d(3, 28), null, "S1");
        cm(ServiceLine.CLEANING, "C2", d(4, 5), null, "S1");    // April, outside Jan-Mar

        KpiCaseMetricsResponse r = service.monthly(JAN, MAR);

        assertThat(month(r, "2026-03").cleaning().cm().repeat()).isEqualTo(1);
        assertThat(r.ticketCount()).isEqualTo(1);   // C2 itself is not counted
    }

    /** Serial is the join, so a robot's history counts wherever it was recorded. */
    @Test
    void repeatsMatchAcrossBoards() {
        cm(ServiceLine.CLEANING, "C1", d(1, 1), null, "S1");
        cm(ServiceLine.DELIVERY, "C2", d(1, 5), null, "S1");

        assertThat(month(service.monthly(JAN, JAN), "2026-01").all().cm().repeat()).isEqualTo(1);
    }

    /** Nothing to match on, so it cannot be shown to have failed — but it is reported. */
    @Test
    void cmWithoutSerialCountsAsFixedAndIsFlagged() {
        cm(ServiceLine.CLEANING, "C1", d(1, 5), null, null);

        CmCounts jan = month(service.monthly(JAN, JAN), "2026-01").cleaning().cm();

        assertThat(jan.firstTimeFix()).isEqualTo(1);
        assertThat(jan.withoutSerial()).isEqualTo(1);
    }

    // ── SLA ─────────────────────────────────────────────────────────────────

    /** Checked within 7 days of the report is within; the 7th day still counts. */
    @Test
    void slaIsOpenToActionWithinSevenDays() {
        cm(ServiceLine.CLEANING, "C1", d(1, 1), d(1, 8), "S1");    // exactly 7
        cm(ServiceLine.CLEANING, "C2", d(1, 1), d(1, 9), "S2");    // 8
        cm(ServiceLine.CLEANING, "C3", d(1, 1), d(1, 1), "S3");    // same day

        CmCounts jan = month(service.monthly(JAN, JAN), "2026-01").cleaning().cm();

        assertThat(jan.slaWithin()).isEqualTo(2);
        assertThat(jan.slaOver()).isEqualTo(1);
        assertThat(jan.slaUnknown()).isZero();
        assertThat(jan.slaWithinRate()).isEqualTo(66.7);
    }

    /** No action recorded is not the same claim as a breach. */
    @Test
    void missingActionDateIsUnknownNotABreach() {
        cm(ServiceLine.CLEANING, "C1", d(1, 1), null, "S1");

        CmCounts jan = month(service.monthly(JAN, JAN), "2026-01").cleaning().cm();

        assertThat(jan.slaUnknown()).isEqualTo(1);
        assertThat(jan.slaOver()).isZero();
        assertThat(jan.slaWithinRate()).isNull();
    }

    /** Installations carry no SLA of their own; the CM counters stay empty. */
    @Test
    void installationsDoNotEnterTheCmCounters() {
        install("I1", d(1, 5), "S1");

        MonthMetrics jan = month(service.monthly(JAN, JAN), "2026-01");

        assertThat(jan.all().installation().total()).isEqualTo(1);
        assertThat(jan.all().cm().total()).isZero();
    }

    // ── shape and guards ────────────────────────────────────────────────────

    @Test
    void emptyMonthsAreZeroFilledWithNullRatesAndDefinitionsAreReturned() {
        KpiCaseMetricsResponse r = service.monthly(YearMonth.of(2026, 5), YearMonth.of(2026, 6));

        assertThat(r.months()).hasSize(2);
        assertThat(r.ticketCount()).isZero();
        assertThat(r.repeatWindowDays()).isEqualTo(14);
        assertThat(r.installFollowUpDays()).isEqualTo(30);
        assertThat(r.provisional()).isTrue();
        assertThat(r.definitions()).containsKeys("firstTimeInstall", "firstTimeFix", "sla", "bucketing", "matching");
        assertThat(month(r, "2026-05").all().cm().firstTimeFixRate()).isNull();
        assertThat(month(r, "2026-05").all().installation().firstTimeRate()).isNull();
    }

    @Test
    void rejectsAnInvertedOrOversizedRange() {
        assertThatThrownBy(() -> service.monthly(MAR, JAN))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("'to'");
        assertThatThrownBy(() -> service.monthly(JAN, JAN.plusMonths(KpiCaseMetricsService.MAX_MONTHS)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("24 months");
    }

    /** A ticket the board no longer returns must not move a past month's number. */
    @Test
    void absentTicketsAreExcluded() {
        KpiCaseTicket gone = cm(ServiceLine.CLEANING, "C1", d(1, 5), d(1, 6), "S1");
        gone.setPresent(false);
        repository.save(gone);

        assertThat(service.monthly(JAN, JAN).ticketCount()).isZero();
    }

    // ── classification by serial (the boards' foreign key) ──────────────────

    /**
     * The installation board never says whether a robot is cleaning or delivery.
     * The CM boards do, and the serial is the same robot, so the installation
     * inherits the line from wherever that serial was serviced.
     */
    @Test
    void installationIsClassifiedByItsSerialAgainstTheCmBoards() {
        install("I1", d(1, 5), "S-CLEAN");
        install("I2", d(1, 6), "S-DELIV");
        cm(ServiceLine.CLEANING, "C1", d(6, 1), null, "S-CLEAN");    // months later, still identifies it
        cm(ServiceLine.DELIVERY, "C2", d(6, 2), null, "S-DELIV");

        KpiCaseMetricsResponse r = service.monthly(JAN, JAN);

        assertThat(month(r, "2026-01").cleaning().installation().total()).isEqualTo(1);
        assertThat(month(r, "2026-01").delivery().installation().total()).isEqualTo(1);
        assertThat(month(r, "2026-01").all().installation().total()).isEqualTo(2);
        assertThat(r.unclassifiedTickets()).isZero();
        // Both CMs are far outside the 30-day window, so neither install failed.
        assertThat(month(r, "2026-01").all().installation().firstTime()).isEqualTo(2);
    }

    /** A robot never serviced cannot be classified; it counts in the total only. */
    @Test
    void unclassifiableInstallationCountsInTheTotalButNeitherColumn() {
        install("I1", d(1, 5), "S-NEVER-SERVICED");

        KpiCaseMetricsResponse r = service.monthly(JAN, JAN);

        assertThat(r.unclassifiedTickets()).isEqualTo(1);
        assertThat(month(r, "2026-01").all().installation().total()).isEqualTo(1);
        assertThat(month(r, "2026-01").cleaning().installation().total()).isZero();
        assertThat(month(r, "2026-01").delivery().installation().total()).isZero();
    }

    /** One robot cannot be both, so a contested serial classifies nothing. */
    @Test
    void aSerialClaimedByBothBoardsIsNotUsedToClassify() {
        install("I1", d(1, 5), "S-BOTH");
        cm(ServiceLine.CLEANING, "C1", d(6, 1), null, "S-BOTH");
        cm(ServiceLine.DELIVERY, "C2", d(6, 2), null, "S-BOTH");

        KpiCaseMetricsResponse r = service.monthly(JAN, JAN);

        assertThat(r.unclassifiedTickets()).isEqualTo(1);
        assertThat(month(r, "2026-01").all().installation().total()).isEqualTo(1);
    }

    // ── category filter: not every row on a KPI board is a KPI case ─────────

    /** A survey job on the installation board is the team's work, not an installation. */
    @Test
    void jobTypesOutsideTheIncludeListAreNotInstallations() {
        install("I1", d(1, 5), "S1", "Installation");
        install("I2", d(1, 6), "S2", "Survey Site");
        install("I3", d(1, 7), "S3", "Transport");

        KpiCaseMetricsResponse r = service.monthly(JAN, JAN);

        assertThat(month(r, "2026-01").all().installation().total()).isEqualTo(1);
        assertThat(r.excludedByCategory()).isEqualTo(2);
        assertThat(r.ticketCount()).isEqualTo(1);
        assertThat(r.definitions()).containsKey("category");
    }

    /** Parts-shipping rows are excluded on the cleaning board; a blank type is included, as the deck does. */
    @Test
    void cmCategoriesAreFilteredAndBlankCountsWhenListed() {
        cm(ServiceLine.CLEANING, "C1", d(1, 1), null, "S1", "Incident case");
        cm(ServiceLine.CLEANING, "C2", d(1, 2), null, "S2", null);            // blank type
        cm(ServiceLine.CLEANING, "C3", d(1, 3), null, "S3", "ส่งอะไหล่");     // parts shipment
        cm(ServiceLine.DELIVERY, "C4", d(1, 4), null, "S4", "Low");           // delivery counts everything

        KpiCaseMetricsResponse r = service.monthly(JAN, JAN);

        assertThat(month(r, "2026-01").cleaning().cm().total()).isEqualTo(2);
        assertThat(month(r, "2026-01").delivery().cm().total()).isEqualTo(1);
        assertThat(r.excludedByCategory()).isEqualTo(1);
    }

    /** A parts-shipping row for the same serial is still evidence the robot came back. */
    @Test
    void anExcludedCategoryStillCountsAsAFollowUp() {
        cm(ServiceLine.CLEANING, "C1", d(1, 1), null, "S1", "Incident case");
        cm(ServiceLine.CLEANING, "C2", d(1, 5), null, "S1", "ส่งอะไหล่");

        CmCounts jan = month(service.monthly(JAN, JAN), "2026-01").cleaning().cm();

        assertThat(jan.total()).isEqualTo(1);
        assertThat(jan.repeat()).isEqualTo(1);
    }
}
