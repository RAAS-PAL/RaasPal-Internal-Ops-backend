package com.raaspal.robotrecommendation.kpi;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.Counts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.MonthMetrics;
import com.raaspal.robotrecommendation.kpi.entity.CaseTicket;
import com.raaspal.robotrecommendation.kpi.entity.ServiceLine;
import com.raaspal.robotrecommendation.kpi.repository.CaseTicketRepository;
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
 * The board-facing maths: Total CM Cases, SLA and First Time Fix per month and
 * per service line, computed from synced tickets. Every rule in
 * {@link KpiCaseMetricsService}'s class comment has a ticket here that lands
 * on each side of it.
 *
 * <p>Boards from {@code kpi-test.properties}: Cleaning {@code 3451717331} with
 * SLA 3/3, Delivery {@code 1647612496} with SLA 3 metro / 5 upcountry; metro
 * provinces Bangkok and Nonthaburi; repeat window 7 days.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:kpi-test.properties")
@Transactional
class KpiCaseMetricsServiceTest {

    private static final String CLEANING_BOARD = "3451717331";
    private static final String DELIVERY_BOARD = "1647612496";
    private static final YearMonth JAN = YearMonth.of(2026, 1);
    private static final YearMonth MAR = YearMonth.of(2026, 3);

    @Autowired private KpiCaseMetricsService service;
    @Autowired private CaseTicketRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private CaseTicket ticket(ServiceLine line, String itemId, LocalDate open, LocalDate close,
                              String serials, String province) {
        String board = line == ServiceLine.CLEANING ? CLEANING_BOARD : DELIVERY_BOARD;
        CaseTicket ticket = CaseTicket.builder()
                .source(CaseTicket.SOURCE_MONDAY)
                .sourceBoardId(board)
                .sourceItemId(itemId)
                .serviceLine(line)
                .openDate(open)
                .closeDate(close)
                .closed(close != null)
                .serialsNormalised(serials)
                .provinceRaw(province)
                .firstSeenAt(LocalDateTime.of(2026, 9, 1, 0, 0))
                .lastSyncedAt(LocalDateTime.of(2026, 9, 8, 1, 30))
                .present(true)
                .build();
        return repository.save(ticket);
    }

    private static MonthMetrics month(KpiCaseMetricsResponse response, String month) {
        return response.months().stream().filter(m -> m.month().equals(month)).findFirst().orElseThrow();
    }

    @Test
    void countsSlaAndRepeatsPerMonthAndServiceLine() {
        // January — cleaning: A closed in 2 days (within 3), then B for the same
        // serial 5 days after A closed: A is a repeat, B is not (nobody follows it).
        ticket(ServiceLine.CLEANING, "A", d(1, 5), d(1, 7), "S1", null);
        ticket(ServiceLine.CLEANING, "B", d(1, 12), null, "S1", null);
        // January — delivery: C took 6 days in Bangkok (limit 3): over.
        //                     D took 5 days, province blank (upcountry limit 5): within.
        ticket(ServiceLine.DELIVERY, "C", d(1, 10), d(1, 16), "S3", "Bangkok");
        ticket(ServiceLine.DELIVERY, "D", d(1, 10), d(1, 15), "S4", null);
        // January — a ticket the board no longer returns must not count.
        CaseTicket gone = ticket(ServiceLine.CLEANING, "H", d(1, 20), d(1, 21), "S8", null);
        gone.setPresent(false);
        repository.save(gone);

        // February — E: open, no serial. J (delivery) and K (cleaning) share a
        // serial two days apart, but on different lines: neither is a repeat.
        ticket(ServiceLine.CLEANING, "E", d(2, 1), null, null, null);
        ticket(ServiceLine.DELIVERY, "J", d(2, 10), d(2, 11), "S2", null);
        ticket(ServiceLine.CLEANING, "K", d(2, 13), null, "S2", null);

        // March — F closed on the 30th; G for the same serial opens 4 April,
        // outside the range but inside the 7-day look-ahead: F is a repeat, G
        // is not counted.
        ticket(ServiceLine.CLEANING, "F", d(3, 28), d(3, 30), "S9", null);
        ticket(ServiceLine.CLEANING, "G", LocalDate.of(2026, 4, 3), null, "S9", null);

        KpiCaseMetricsResponse response = service.monthly(JAN, MAR);

        assertThat(response.from()).isEqualTo("2026-01");
        assertThat(response.to()).isEqualTo("2026-03");
        assertThat(response.months()).extracting(MonthMetrics::month).containsExactly("2026-01", "2026-02", "2026-03");
        assertThat(response.ticketCount()).isEqualTo(8);
        assertThat(response.repeatWindowDays()).isEqualTo(7);
        assertThat(response.provisional()).isTrue();
        assertThat(response.lastSyncedAt()).isEqualTo(LocalDateTime.of(2026, 9, 8, 1, 30));
        assertThat(response.definitions()).containsKeys("total", "closed", "sla", "repeat", "firstTimeFix");

        MonthMetrics jan = month(response, "2026-01");
        assertThat(jan.all().total()).isEqualTo(4);
        Counts janCleaning = jan.cleaning();
        assertThat(janCleaning.total()).isEqualTo(2);
        assertThat(janCleaning.closed()).isEqualTo(1);
        assertThat(janCleaning.slaWithin()).isEqualTo(1);
        assertThat(janCleaning.slaOver()).isZero();
        assertThat(janCleaning.slaUnknown()).isEqualTo(1);
        assertThat(janCleaning.repeat()).isEqualTo(1);
        assertThat(janCleaning.firstTimeFix()).isEqualTo(1);
        assertThat(janCleaning.firstTimeFixRate()).isEqualTo(50.0);
        assertThat(janCleaning.slaWithinRate()).isEqualTo(100.0);
        Counts janDelivery = jan.delivery();
        assertThat(janDelivery.total()).isEqualTo(2);
        assertThat(janDelivery.slaWithin()).isEqualTo(1);
        assertThat(janDelivery.slaOver()).isEqualTo(1);
        assertThat(janDelivery.slaWithinRate()).isEqualTo(50.0);
        assertThat(janDelivery.repeat()).isZero();

        MonthMetrics feb = month(response, "2026-02");
        assertThat(feb.all().total()).isEqualTo(3);
        assertThat(feb.all().repeat()).isZero();
        assertThat(feb.all().firstTimeFix()).isEqualTo(3);
        assertThat(feb.all().withoutSerial()).isEqualTo(1);
        assertThat(feb.cleaning().slaUnknown()).isEqualTo(2);
        assertThat(feb.cleaning().slaWithinRate()).isNull();
        assertThat(feb.delivery().slaWithin()).isEqualTo(1);

        MonthMetrics mar = month(response, "2026-03");
        assertThat(mar.all().total()).isEqualTo(1);
        assertThat(mar.cleaning().repeat()).isEqualTo(1);
        assertThat(mar.cleaning().firstTimeFix()).isZero();
        assertThat(mar.cleaning().firstTimeFixRate()).isEqualTo(0.0);

        assertThat(response.totals().all().total()).isEqualTo(8);
        assertThat(response.totals().cleaning().total()).isEqualTo(5);
        assertThat(response.totals().delivery().total()).isEqualTo(3);
        assertThat(response.totals().all().repeat()).isEqualTo(2);
        assertThat(response.totals().all().firstTimeFixRate()).isEqualTo(75.0);
    }

    /** At exactly the limit a case is still within SLA; lateness starts the day after. */
    @Test
    void exactlyTheSlaLimitIsWithin() {
        ticket(ServiceLine.DELIVERY, "M", d(1, 1), d(1, 4), "S1", "bangkok");      // 3 days, metro limit 3
        ticket(ServiceLine.DELIVERY, "N", d(1, 1), d(1, 5), "S2", "Nonthaburi");   // 4 days, metro limit 3
        ticket(ServiceLine.DELIVERY, "O", d(1, 1), d(1, 6), "S3", "Rayong");       // 5 days, upcountry limit 5

        Counts delivery = service.monthly(JAN, JAN).totals().delivery();

        assertThat(delivery.slaWithin()).isEqualTo(2);
        assertThat(delivery.slaOver()).isEqualTo(1);
    }

    /** A follower more than the window after the close is a new case, not a repeat. */
    @Test
    void followerOutsideTheWindowIsNotARepeat() {
        ticket(ServiceLine.CLEANING, "P", d(1, 1), d(1, 2), "S1", null);
        ticket(ServiceLine.CLEANING, "Q", d(1, 10), null, "S1", null);   // 8 days after P closed

        Counts cleaning = service.monthly(JAN, JAN).totals().cleaning();

        assertThat(cleaning.repeat()).isZero();
        assertThat(cleaning.firstTimeFix()).isEqualTo(2);
    }

    /** A ticket that never closed is anchored on its open date instead. */
    @Test
    void openTicketAnchorsTheWindowOnItsOpenDate() {
        ticket(ServiceLine.CLEANING, "R", d(1, 1), null, "S1", null);
        ticket(ServiceLine.CLEANING, "S", d(1, 6), null, "S1", null);

        Counts cleaning = service.monthly(JAN, JAN).totals().cleaning();

        assertThat(cleaning.repeat()).isEqualTo(1);
    }

    /** A ticket naming several robots is followed if any one of them comes back. */
    @Test
    void anySharedSerialCountsAsAFollower() {
        ticket(ServiceLine.CLEANING, "T", d(1, 1), d(1, 2), "S1|S2", null);
        ticket(ServiceLine.CLEANING, "U", d(1, 5), null, "S2", null);

        assertThat(service.monthly(JAN, JAN).totals().cleaning().repeat()).isEqualTo(1);
    }

    @Test
    void monthsWithNothingOpenedAreZeroFilledWithNullRates() {
        KpiCaseMetricsResponse response = service.monthly(YearMonth.of(2026, 5), YearMonth.of(2026, 6));

        assertThat(response.months()).hasSize(2);
        assertThat(response.ticketCount()).isZero();
        assertThat(response.lastSyncedAt()).isNull();
        Counts may = month(response, "2026-05").all();
        assertThat(may.total()).isZero();
        assertThat(may.slaWithinRate()).isNull();
        assertThat(may.firstTimeFixRate()).isNull();
    }

    @Test
    void rejectsAnInvertedOrOversizedRange() {
        assertThatThrownBy(() -> service.monthly(MAR, JAN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("'to'");
        assertThatThrownBy(() -> service.monthly(JAN, JAN.plusMonths(KpiCaseMetricsService.MAX_MONTHS)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("24 months");
    }

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }
}
