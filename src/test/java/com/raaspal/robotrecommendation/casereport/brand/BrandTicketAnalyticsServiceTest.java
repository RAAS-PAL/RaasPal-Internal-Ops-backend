package com.raaspal.robotrecommendation.casereport.brand;

import com.raaspal.robotrecommendation.casereport.brand.dto.BrandTicket;
import com.raaspal.robotrecommendation.casereport.brand.dto.BrandTicketSummary;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrandTicketAnalyticsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    private final BrandTicketAnalyticsService analytics = new BrandTicketAnalyticsService();

    private static BrandTicketProperties.Brand autoxing() {
        BrandTicketProperties.Brand b = new BrandTicketProperties.Brand();
        b.setKey("autoxing");
        b.setLabel("AutoXing");
        b.setBoardId("1647612496");
        b.setModels(List.of("Zara", "Zara L300", "Zara Bot L600", "D150"));
        b.setNameTerms(List.of("Zara", "D150"));
        return b;
    }

    private static BrandTicket ticket(String id, String serial, String open, String action,
                                      boolean isOpen, String rootCause, String site) {
        LocalDate openDate = LocalDate.parse(open);
        LocalDate actionDate = action == null ? null : LocalDate.parse(action);
        return BrandTicket.builder()
                .id(id).itemId(id).name("ZARA " + id).group(isOpen ? "All Case" : "DONE-Ticket")
                .open(isOpen).status(isOpen ? "Check" : "Done").model("Zara").serial(serial)
                .rootCause(rootCause).project(site)
                .openDate(openDate).reActionDate(actionDate)
                .daysToAction(actionDate == null ? null : (int) (actionDate.toEpochDay() - openDate.toEpochDay()))
                .ageDays(isOpen ? (int) (TODAY.toEpochDay() - openDate.toEpochDay()) : null)
                .lastSyncedAt(LocalDateTime.of(2026, 9, 16, 6, 15))
                .comments(List.of())
                .build();
    }

    @Test
    void matcherAcceptsModelLabelOrNameFragmentOnly() {
        BrandMatcher m = new BrandMatcher(autoxing());
        assertThat(m.matches("Zara L300", "anything")).isTrue();
        assertThat(m.matches("Bella", "(ZARA) Kubota robot stuck")).isTrue();
        assertThat(m.matches("Pudu 1", "D150 - charging fault")).isTrue();
        assertThat(m.matches("Bella", "PuduBot cannot dock")).isFalse();
        assertThat(m.matches(null, null)).isFalse();
    }

    @Test
    void kpisFollowTheReTeamRules() {
        List<BrandTicket> all = List.of(
                // on time (3 days), followed by a repeat on the same serial 10 days later
                ticket("1", "SN-A", "2026-09-01", "2026-09-04", false, "Hardware", "Kubota"),
                ticket("2", "SN-A", "2026-09-11", "2026-09-12", true, "Hardware", "Kubota"),
                // SLA breach (9 days), no repeat
                ticket("3", "SN-B", "2026-08-20", "2026-08-29", false, "Software", "Nikon"),
                // no action yet, open 40 days
                ticket("4", "SN-C", "2026-08-07", null, true, null, "Nikon"),
                // last month, outside the range below
                ticket("5", "SN-D", "2026-07-15", "2026-07-16", false, "WIFI", "Essilor"));

        BrandTicketSummary s = analytics.summarise(autoxing(), all,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30), TODAY);

        assertThat(s.totals().tickets()).isEqualTo(4);
        assertThat(s.totals().open()).isEqualTo(2);
        assertThat(s.totals().robots()).isEqualTo(3);

        assertThat(s.kpis().openNow()).isEqualTo(2);
        assertThat(s.kpis().oldestOpenDays()).isEqualTo(40);
        assertThat(s.kpis().thisMonth()).isEqualTo(2);
        assertThat(s.kpis().lastMonth()).isEqualTo(2);
        assertThat(s.kpis().monthDeltaPct()).isEqualTo(0.0);
        // days to action: 3, 1, 9 -> median 3; within 7 days: 2 of 3
        assertThat(s.kpis().medianDaysToAction()).isEqualTo(3.0);
        assertThat(s.kpis().slaWithin7Pct()).isEqualTo(66.7);
        // repeat: ticket 1 is followed within 14 days on SN-A; 4 sampled -> 25%
        assertThat(s.kpis().repeatRatePct()).isEqualTo(25.0);

        assertThat(s.monthly()).extracting(BrandTicketSummary.MonthPoint::month)
                .containsExactly("2026-08", "2026-09");
        assertThat(s.monthly().get(1).opened()).isEqualTo(2);
        assertThat(s.rootCauses().get(0)).isEqualTo(new BrandTicketSummary.Count("Hardware", 2));
        assertThat(s.rootCauses()).extracting(BrandTicketSummary.Count::label).contains("(blank)");
        assertThat(s.aging()).extracting(BrandTicketSummary.Count::count).containsExactly(1, 0, 0, 1);
        assertThat(s.topSites().get(0).label()).isIn("Kubota", "Nikon");
        assertThat(s.repeatRobots()).hasSize(1);
        assertThat(s.repeatRobots().get(0).serial()).isEqualTo("SN-A");
        assertThat(s.repeatRobots().get(0).count()).isEqualTo(2);
    }

    @Test
    void emptyRangeProducesNullsNotZeros() {
        BrandTicketSummary s = analytics.summarise(autoxing(), List.of(), null, null, TODAY);
        assertThat(s.totals().tickets()).isZero();
        assertThat(s.kpis().medianDaysToAction()).isNull();
        assertThat(s.kpis().slaWithin7Pct()).isNull();
        assertThat(s.kpis().repeatRatePct()).isNull();
        assertThat(s.monthly()).isEmpty();
    }
}
