package com.raaspal.robotrecommendation.casereport;

import com.raaspal.robotrecommendation.casereport.service.AirportTickets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The airport predicate, and in particular the deliberate difference between its two
 * entry points — the one the Cleaning sheet excludes on reads fewer fields than the one
 * the AOTGA sheet includes on.
 */
class AirportTicketsTest {

    @Test
    void matchesAnAotgaTag() {
        assertThat(AirportTickets.matches("AOTGA-:-DMK", null)).isTrue();
        assertThat(AirportTickets.matches("AOTGA-BKK", "")).isTrue();
    }

    @Test
    void matchesEitherSpellingOfTheThaiWordAndTheNamedAirports() {
        assertThat(AirportTickets.matches(null, "ท่าอากาศยานภูเก็ต")).isTrue();
        assertThat(AirportTickets.matches(null, "ท่าอาศยานเชียงใหม่")).isTrue();
        assertThat(AirportTickets.matches(null, "สนามบินหาดใหญ่")).isTrue();
        assertThat(AirportTickets.matches(null, "สุวรรณภูมิ")).isTrue();
        assertThat(AirportTickets.matches(null, "ดอนเมือง")).isTrue();
        assertThat(AirportTickets.matches(null, "แม่ฟ้าหลวง")).isTrue();
    }

    @Test
    void leavesOrdinaryCleaningSitesAlone() {
        assertThat(AirportTickets.matches(null, "One Bangkok")).isFalse();
        assertThat(AirportTickets.matches(null, "โรงพยาบาลเซนต์หลุยส์")).isFalse();
    }

    /**
     * The reason bare city names are not in the list: Makro has a Hat Yai branch, and
     * misfiling it as an airport would take it off its own sheet.
     */
    @Test
    void doesNotClaimMakroHatYai() {
        assertThat(AirportTickets.matches(null, "Makroหาดใหญ่")).isFalse();
    }

    /**
     * "aot " carries a significant trailing space, so a field <em>ending</em> in "aot" is
     * not an airport -- there is nothing after it to match.
     */
    @Test
    void doesNotMatchAFieldMerelyEndingInAot() {
        assertThat(AirportTickets.matches(null, "Somewhere aot")).isFalse();
        assertThat(AirportTickets.matches(null, "aot terminal")).isTrue();
    }

    /**
     * A tag of exactly "AOT" does match, because the separator the join inserts between
     * the fields supplies the space. Intended: such a tag names the customer.
     */
    @Test
    void matchesATagOfExactlyAot() {
        assertThat(AirportTickets.matches("AOT", "One Bangkok")).isTrue();
    }

    @Test
    void bothEntryPointsAgreeWhenTheTagOrBranchCarriesTheMarker() {
        assertThat(AirportTickets.matches("AOTGA-BKK", null)).isTrue();
        assertThat(AirportTickets.matchesIncludingName("anything", "AOTGA-BKK", null)).isTrue();
    }

    /**
     * The accepted asymmetry: a prefix written only in the ticket name is seen by the
     * AOTGA sheet and not by the Cleaning sheet's exclusion, so such a ticket appears on
     * both rather than on neither.
     */
    @Test
    void onlyTheWiderEntryPointReadsTheTicketName() {
        String name = "AOTGA-BKK M75 : ท่าอากาศยานสุวรรณภูมิ";

        assertThat(AirportTickets.matches(null, null)).isFalse();
        assertThat(AirportTickets.matchesIncludingName(name, null, null)).isTrue();
    }

    @Test
    void handlesAllFieldsNull() {
        assertThat(AirportTickets.matches(null, null)).isFalse();
        assertThat(AirportTickets.matchesIncludingName(null, null, null)).isFalse();
    }
}
