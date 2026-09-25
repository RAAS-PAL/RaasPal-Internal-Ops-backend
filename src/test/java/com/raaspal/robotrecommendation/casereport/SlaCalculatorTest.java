package com.raaspal.robotrecommendation.casereport;

import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.service.SlaCalculator;
import com.raaspal.robotrecommendation.casereport.service.SlaStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SLA rule, pinned.
 *
 * <p>Worth testing thoroughly rather than lightly: this verdict is printed beside a
 * customer's case, and both directions of error are expensive. A false "over SLA" accuses
 * RAASPAL of lateness it does not own; a false "Within SLA" tells a customer their case is
 * on time when it is overdue, and nobody re-reads a row that already looks fine.
 *
 * <p>Cases are expressed in terms of the <em>reported</em> Days value — the number the
 * report actually prints — because that is what the team talks in. The count excludes the
 * open day, so it is the plain date difference and a case opened today reads 0.
 *
 * <p>A plain unit test, no Spring context: the class takes its config through the
 * constructor precisely so this is possible.
 */
class SlaCalculatorTest {

    /** The six greater-Bangkok provinces as the board spells them, plus Thai forms. */
    private static final List<String> METRO = List.of(
            "Bangkok", "Nonthaburi", "Pathum Thani", "Samut Prakan",
            "Samut Sakhon", "Nakhon Pathom",
            "กรุงเทพ", "กรุงเทพมหานคร", "กทม", "นนทบุรี",
            "ปทุมธานี", "สมุทรปราการ", "สมุทรสาคร", "นครปฐม");

    /** MK / Yayoi / Bonus Suki delivery. */
    private static final int DELIVERY_METRO = 3;
    private static final int DELIVERY_UPCOUNTRY = 5;

    /** Cleaning, where both thresholds are the same and province never applies. */
    private static final int CLEANING_ANYWHERE = 3;

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 9);

    private final SlaCalculator calculator = new SlaCalculator(METRO);

    /** Open date that produces the given reported Days, counting exclusively. */
    private static LocalDate openedFor(int reportedDays) {
        return AS_OF.minusDays(reportedDays);
    }

    private SlaStatus delivery(String province, int reportedDays) {
        return calculator.evaluate(null, null, province, openedFor(reportedDays), AS_OF,
                DELIVERY_METRO, DELIVERY_UPCOUNTRY);
    }

    private SlaStatus cleaning(String province, int reportedDays) {
        return calculator.evaluate(null, null, province, openedFor(reportedDays), AS_OF,
                CLEANING_ANYWHERE, CLEANING_ANYWHERE);
    }

    /**
     * The day count excludes the day the case opened, so a case opened today reads 0.
     *
     * <p>The RE team's files disagree: the 09 September 2026 workbook counted inclusively
     * (M154, opened 15 Aug, printed 26) while the 11 September report counted exclusively
     * (M154 printed 27 on the 11th, and three cases opened that day printed 0). Exclusive
     * was chosen on 2026-09-11 to match the newer file; a reviewer can overtype one row.
     */
    @Test
    void daysOpenExcludesTheDayTheCaseOpened() {
        assertThat(SlaCalculator.daysOpen(AS_OF, AS_OF)).isEqualTo(0);
        assertThat(SlaCalculator.daysOpen(AS_OF.minusDays(1), AS_OF)).isEqualTo(1);
        assertThat(SlaCalculator.daysOpen(LocalDate.of(2026, 9, 7), AS_OF)).isEqualTo(2);

        // M154 โลตัส จันทบุรี on the 11 Sep report: opened 15 Aug, printed as 27 days.
        assertThat(SlaCalculator.daysOpen(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 9, 11)))
                .isEqualTo(27);
    }

    /**
     * The boundary, and the single easiest thing to get wrong.
     *
     * <p>Compared with {@code >}, not {@code >=}: at exactly the limit a case is still
     * within SLA and lateness starts the day after. The 09 Sep report shows M442
     * บิ๊กซี-กัลปพฤกษ์ (Bangkok) at Days = 3 printing "Within SLA", which fixes this.
     */
    @Test
    void aCaseAtExactlyTheLimitIsStillWithinSla() {
        assertThat(delivery("Bangkok", 2)).isEqualTo(SlaStatus.WITHIN);
        assertThat(delivery("Bangkok", 3)).isEqualTo(SlaStatus.WITHIN);
        assertThat(delivery("Bangkok", 4)).isEqualTo(SlaStatus.BREACHED);

        assertThat(delivery("ชลบุรี", 5)).isEqualTo(SlaStatus.WITHIN);
        assertThat(delivery("ชลบุรี", 6)).isEqualTo(SlaStatus.BREACHED);
    }

    /** Why province matters at all: the same day count, two different verdicts. */
    @Test
    void upcountryGetsFiveDaysWhereGreaterBangkokGetsThree() {
        assertThat(delivery("สงขลา", 5)).isEqualTo(SlaStatus.WITHIN);
        assertThat(delivery("Bangkok", 5)).isEqualTo(SlaStatus.BREACHED);
    }

    /** Every one of the six, so a typo in the config list fails here and not in front of a customer. */
    @Test
    void allSixGreaterBangkokProvincesGetTheShorterThreshold() {
        for (String metro : List.of("Bangkok", "Nonthaburi", "Pathum Thani",
                                    "Samut Prakan", "Samut Sakhon", "Nakhon Pathom")) {
            assertThat(delivery(metro, 4))
                    .as("%s is one of the six and should be on the 3-day threshold", metro)
                    .isEqualTo(SlaStatus.BREACHED);
        }
    }

    /**
     * The board spells the six in Latin today; that is the habit of whoever built the
     * dropdown, not a rule. A Thai spelling must reach the same threshold.
     */
    @Test
    void theThaiSpellingOfAMetroProvinceIsStillMetro() {
        assertThat(delivery("กรุงเทพมหานคร", 4)).isEqualTo(SlaStatus.BREACHED);
        assertThat(delivery("นนทบุรี", 4)).isEqualTo(SlaStatus.BREACHED);
        assertThat(delivery("กทม", 4)).isEqualTo(SlaStatus.BREACHED);
    }

    /** Values are typed and pasted by people, so matching cannot be exact. */
    @Test
    void matchingIgnoresCaseAndSurroundingWhitespace() {
        assertThat(delivery("  bangkok  ", 4)).isEqualTo(SlaStatus.BREACHED);
        assertThat(delivery("PATHUM  THANI", 4)).isEqualTo(SlaStatus.BREACHED);
    }

    /**
     * A missing province leaves the verdict blank rather than guessing.
     *
     * <p>Defaulting to 5 days would show a genuinely late Bangkok case as on time;
     * defaulting to 3 would accuse RAASPAL of lateness it may not own.
     */
    @Test
    void aDeliveryCaseWithNoProvinceGetsNoVerdict() {
        assertThat(delivery(null, 10)).isEqualTo(SlaStatus.UNKNOWN);
        assertThat(delivery("", 10)).isEqualTo(SlaStatus.UNKNOWN);
        assertThat(delivery("   ", 10)).isEqualTo(SlaStatus.UNKNOWN);
    }

    /**
     * The nuance that keeps cleaning reports working: with both thresholds equal the
     * province is never consulted, so a cleaning ticket without one still gets a verdict.
     *
     * <p>Insisting on a province here would blank a column that was never
     * province-dependent — and the cleaning board has no Province field to fill in.
     */
    @Test
    void aCleaningCaseNeedsNoProvinceBecauseBothThresholdsMatch() {
        assertThat(cleaning(null, 3)).isEqualTo(SlaStatus.WITHIN);
        assertThat(cleaning(null, 4)).isEqualTo(SlaStatus.BREACHED);
        assertThat(cleaning("ระยอง", 4)).isEqualTo(SlaStatus.BREACHED);
    }

    /** On Hold outranks the arithmetic: the clock is not ours to run. */
    @Test
    void anOnHoldCaseIsNeitherWithinNorBreachedHoweverOldItIs() {
        // One Bangkok on the 09 Sep cleaning sheet: 56 days, and printed "On Hold".
        assertThat(calculator.evaluate("On Hold", null, "Bangkok", openedFor(56), AS_OF, 3, 3))
                .isEqualTo(SlaStatus.ON_HOLD);
        assertThat(calculator.evaluate(null, "On Hold", "Bangkok", openedFor(56), AS_OF, 3, 5))
                .isEqualTo(SlaStatus.ON_HOLD);
        assertThat(calculator.evaluate("on hold - waiting customer", null, "Bangkok",
                openedFor(9), AS_OF, 3, 5))
                .isEqualTo(SlaStatus.ON_HOLD);
    }

    /**
     * On Hold is decided before the province is needed, so a held case with no province
     * reads as held rather than unknown. The two reasons for "no colour" must not merge.
     */
    @Test
    void onHoldIsDecidedBeforeTheProvinceIsNeeded() {
        assertThat(calculator.evaluate("On Hold", null, null, openedFor(9), AS_OF, 3, 5))
                .isEqualTo(SlaStatus.ON_HOLD);
    }

    /**
     * The summary counts the customer's holds apart from RAASPAL's: Status is the
     * customer's, Sup Status is ours, and a case held in both is the customer's.
     */
    @Test
    void statusIsTheCustomersHoldAndSupStatusIsOurs() {
        assertThat(SlaCalculator.heldBy("On Hold", null)).isEqualTo(CaseReportRow.HELD_BY_CUSTOMER);
        assertThat(SlaCalculator.heldBy("Pending", "On Hold")).isEqualTo(CaseReportRow.HELD_BY_RAASPAL);
        assertThat(SlaCalculator.heldBy("On Hold", "On Hold")).isEqualTo(CaseReportRow.HELD_BY_CUSTOMER);
        assertThat(SlaCalculator.heldBy("Pending", "รออะไหล่")).isNull();
        assertThat(SlaCalculator.heldBy(null, null)).isNull();
    }

    /** No open date, no day count, no verdict. */
    @Test
    void aCaseWithNoOpenDateGetsNoVerdict() {
        assertThat(calculator.evaluate(null, null, "Bangkok", null, AS_OF, 3, 5))
                .isEqualTo(SlaStatus.UNKNOWN);
    }

    /**
     * The rows of the 09 September 2026 delivery sheet, replayed.
     *
     * <p>Fourteen of the sixteen agree with the rule. The two that do not are recorded in
     * the next test rather than quietly dropped.
     */
    @Test
    void reproducesTheLiveReportWhereTheLiveReportFollowsTheRule() {
        // metro
        assertThat(delivery("Bangkok", 17)).isEqualTo(SlaStatus.BREACHED);       // M575 หนองจอก
        assertThat(delivery("Samut Sakhon", 14)).isEqualTo(SlaStatus.BREACHED);  // M386
        assertThat(delivery("Bangkok", 3)).isEqualTo(SlaStatus.WITHIN);          // M442
        assertThat(delivery("Bangkok", 2)).isEqualTo(SlaStatus.WITHIN);          // M177
        assertThat(delivery("Samut Prakan", 2)).isEqualTo(SlaStatus.WITHIN);     // โลตัส บางพลี
        assertThat(delivery("Bangkok", 7)).isEqualTo(SlaStatus.BREACHED);        // Y084 ศิริราช

        // upcountry
        assertThat(delivery("จันทบุรี", 26)).isEqualTo(SlaStatus.BREACHED);       // M154
        assertThat(delivery("สิงห์บุรี", 17)).isEqualTo(SlaStatus.BREACHED);       // Nikon
        assertThat(delivery("ระยอง", 2)).isEqualTo(SlaStatus.WITHIN);            // M569
        assertThat(delivery("ชลบุรี", 3)).isEqualTo(SlaStatus.WITHIN);            // Essilor x2
        assertThat(delivery("ชลบุรี", 2)).isEqualTo(SlaStatus.WITHIN);            // Valeo
    }

    /**
     * The two rows where the generated report will deliberately differ from the 09 Sep
     * file, kept as a test so the difference is a decision on record rather than a
     * surprise the first time someone compares the two side by side.
     *
     * <p>Both are outside the six provinces and past 5 days, so both are over SLA. The
     * spreadsheet prints "Within SLA" for each; the team confirmed the rule, so the
     * spreadsheet is what is wrong.
     */
    @Test
    void marksOverSlaTheTwoRowsTheHandBuiltSheetGotWrong() {
        // M453 โรบินสัน ฉะเชิงเทรา — Chachoengsao, 7 days, file says "Within SLA"
        assertThat(delivery("ฉะเชิงเทรา", 7)).isEqualTo(SlaStatus.BREACHED);

        // M057 ศรีราชานคร — Chonburi, 6 days, file says "Within SLA"
        assertThat(delivery("ชลบุรี", 6)).isEqualTo(SlaStatus.BREACHED);
    }
}
