package com.raaspal.robotrecommendation.casereport;

import com.raaspal.robotrecommendation.casereport.service.SolutionLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The date-range clean-up applied to a model-written Solution line.
 *
 * <p>The first inputs are the actual strings Haiku produced on 2026-09-11, not invented
 * ones. The edge cases after them are constructed.
 */
class SolutionLineTest {

    /** The case that prompted this: a state running from 25 August to 11 September. */
    /** The 16 September 2026 workbook: every Solution cell is a stack of dated lines. */
    @Test
    void oneEntryPerLineBreaksBeforeEveryDatedEntry() {
        String line = "01-Sep อยู่ระหว่างตรวจสอบและเบิกอะไหล่เบ้าชาร์จ 02-Sep เจ้าหน้าที่เข้าดำเนินการเปลี่ยนเบ้าชาร์จ "
                + "15-16 Sep รอเบิกอะไหล่ให้ 25-26-Aug รออะไหล่แบตเตอรี่";
        assertThat(SolutionLine.oneEntryPerLine(line)).isEqualTo(
                "01-Sep อยู่ระหว่างตรวจสอบและเบิกอะไหล่เบ้าชาร์จ\n"
                        + "02-Sep เจ้าหน้าที่เข้าดำเนินการเปลี่ยนเบ้าชาร์จ\n"
                        + "15-16 Sep รอเบิกอะไหล่ให้\n"
                        + "25-26-Aug รออะไหล่แบตเตอรี่");
    }

    /** "เข้า PM 21-Sep" closes an entry; a date with no phrase after it is not a new one. */
    @Test
    void aDateThatEndsAPhraseDoesNotStartALine() {
        String line = "15-Sep รอคลิปประกอบเคลม 16-Sep อยู่ระหว่างประสานงานติดตั้ง Clip Lock เข้า PM 21-Sep";
        assertThat(SolutionLine.oneEntryPerLine(line)).isEqualTo(
                "15-Sep รอคลิปประกอบเคลม\n16-Sep อยู่ระหว่างประสานงานติดตั้ง Clip Lock เข้า PM 21-Sep");
    }

    /** The model's own newlines, or a mix, come out the same as spaces would. */
    @Test
    void whitespaceFromTheModelIsFoldedBeforeTheBreaksGoIn() {
        assertThat(SolutionLine.oneEntryPerLine("12-Sep เบิกอะไหล่\n\n14-Sep  จัดส่งอะไหล่ \n 17-Sep เจ้าหน้าที่เข้าดำเนินการ"))
                .isEqualTo("12-Sep เบิกอะไหล่\n14-Sep จัดส่งอะไหล่\n17-Sep เจ้าหน้าที่เข้าดำเนินการ");
        assertThat(SolutionLine.oneEntryPerLine("16-Sep อยู่ระหว่างตรวจสอบ")).isEqualTo("16-Sep อยู่ระหว่างตรวจสอบ");
        assertThat(SolutionLine.oneEntryPerLine(null)).isNull();
        assertThat(SolutionLine.oneEntryPerLine("  ")).isEqualTo("  ");
    }

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

    /** The same range with a hyphen before the month, which the model also writes. */
    @Test
    void theHyphenatedFormIsSplitTheSameWay() {
        assertThat(SolutionLine.splitCrossMonthRanges("25-11-Sep อยู่ระหว่างเบิกอะไหล่", 2026))
                .isEqualTo("25-31 Aug อยู่ระหว่างเบิกอะไหล่ 01-11 Sep อยู่ระหว่างเบิกอะไหล่");
    }

    /** Hyphenated but inside one month: rewritten in the workbook's form, neighbours intact. */
    @Test
    void aHyphenatedRangeWithinOneMonthTakesTheWorkbookForm() {
        assertThat(SolutionLine.splitCrossMonthRanges(
                "25-26-Aug รออะไหล่แบตเตอรี่ 04-09-Sep อยู่ระหว่างเบิกอะไหล่", 2026))
                .isEqualTo("25-26 Aug รออะไหล่แบตเตอรี่ 04-09 Sep อยู่ระหว่างเบิกอะไหล่");
    }

    /** A state that began on the month's last day is one date there, not 31-31 Aug. */
    @Test
    void aOneDaySideOfTheSplitIsASingleDate() {
        assertThat(SolutionLine.splitCrossMonthRanges("31-02 Sep รออะไหล่", 2026))
                .isEqualTo("31-Aug รออะไหล่ 01-02 Sep รออะไหล่");
        assertThat(SolutionLine.splitCrossMonthRanges("28-01 Mar รออะไหล่", 2026))
                .isEqualTo("28-Feb รออะไหล่ 01-Mar รออะไหล่");
    }

    /** There is no 30 February, so this is a wrong date rather than a crossing. Untouched. */
    @Test
    void aFirstDayTheEarlierMonthDoesNotHaveIsLeftAsWritten() {
        assertThat(SolutionLine.splitCrossMonthRanges("30-05 Mar รออะไหล่", 2026))
                .isEqualTo("30-05 Mar รออะไหล่");
    }

    @Test
    void emptyAndNullPassThrough() {
        assertThat(SolutionLine.splitCrossMonthRanges(null, 2026)).isNull();
        assertThat(SolutionLine.splitCrossMonthRanges("", 2026)).isEmpty();
    }
}
