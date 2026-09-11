package com.raaspal.robotrecommendation.casereport;

import com.raaspal.robotrecommendation.casereport.service.SolutionLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The date-range clean-up applied to a model-written Solution line.
 *
 * <p>The inputs are the actual strings Haiku produced on 2026-09-11, not invented ones.
 */
class SolutionLineTest {

    /** The case that prompted this: a state running from 25 August to 11 September. */
    @Test
    void aRangeThatCrossesIntoTheNextMonthIsSplitAtTheMonthEnd() {
        String fixed = SolutionLine.splitCrossMonthRanges(
                "25-11 Sep อยู่ระหว่างเบิกอะไหล่", 2026);

        assertThat(fixed).isEqualTo(
                "25-31 Aug อยู่ระหว่างเบิกอะไหล่ 01-11 Sep อยู่ระหว่างเบิกอะไหล่");
    }

    /** The same fault embedded in a full line, with entries either side of it. */
    @Test
    void onlyTheOffendingRangeChangesAndItsNeighboursSurvive() {
        String line = "17-Aug อยู่ระหว่างตรวจสอบและเสนอราคาแบตเตอรี่ 18-Aug รอ MK อนุมัติใบเสนอราคา "
                + "25-Aug ลูกค้าอนุมัติเบิกอะไหล่ให้ 25-11 Sep อยู่ระหว่างเบิกอะไหล่";

        assertThat(SolutionLine.splitCrossMonthRanges(line, 2026)).isEqualTo(
                "17-Aug อยู่ระหว่างตรวจสอบและเสนอราคาแบตเตอรี่ 18-Aug รอ MK อนุมัติใบเสนอราคา "
                        + "25-Aug ลูกค้าอนุมัติเบิกอะไหล่ให้ "
                        + "25-31 Aug อยู่ระหว่างเบิกอะไหล่ 01-11 Sep อยู่ระหว่างเบิกอะไหล่");
    }

    /** A range that stays inside one month is exactly what the workbook writes — untouched. */
    @Test
    void aRangeWithinOneMonthIsLeftAlone() {
        String line = "25-26 Aug รออะไหล่แบตเตอรี่ 04-09 Sep อยู่ระหว่างเบิกอะไหล่";
        assertThat(SolutionLine.splitCrossMonthRanges(line, 2026)).isEqualTo(line);
    }

    /** Plain dated entries contain no range and must not be mistaken for one. */
    @Test
    void singleDatedEntriesAreLeftAlone() {
        String line = "17-Aug อยู่ระหว่างตรวจสอบ 03-Sep รอน้ำยาเคลือบบอร์ด 11-Sep เจ้าหน้าที่เข้าซ่อม";
        assertThat(SolutionLine.splitCrossMonthRanges(line, 2026)).isEqualTo(line);
    }

    /** February's length depends on the year, and the year is the report's. */
    @Test
    void theMonthEndRespectsLeapYears() {
        assertThat(SolutionLine.splitCrossMonthRanges("27-03 Mar รออะไหล่", 2028))
                .isEqualTo("27-29 Feb รออะไหล่ 01-03 Mar รออะไหล่");
        assertThat(SolutionLine.splitCrossMonthRanges("27-03 Mar รออะไหล่", 2026))
                .isEqualTo("27-28 Feb รออะไหล่ 01-03 Mar รออะไหล่");
    }

    /** A state running over New Year: December belongs to the previous year. */
    @Test
    void aRangeIntoJanuarySplitsAtTheThirtyFirstOfDecember() {
        assertThat(SolutionLine.splitCrossMonthRanges("28-05 Jan รออะไหล่", 2027))
                .isEqualTo("28-31 Dec รออะไหล่ 01-05 Jan รออะไหล่");
    }

    @Test
    void emptyAndNullPassThrough() {
        assertThat(SolutionLine.splitCrossMonthRanges(null, 2026)).isNull();
        assertThat(SolutionLine.splitCrossMonthRanges("", 2026)).isEmpty();
    }
}
