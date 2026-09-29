package com.raaspal.robotrecommendation.casereport.adapters.googlesheet;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unformatted Sheets values: numbers that must not grow a ".0", and dates that arrive as
 * serial day counts unless someone typed them as text.
 */
class SheetCellsTest {

    @Test
    void wholeNumbersPrintWithoutADecimal() {
        assertThat(SheetCells.text(1)).isEqualTo("1");
        assertThat(SheetCells.text(1.0)).isEqualTo("1");
        assertThat(SheetCells.text(12.50)).isEqualTo("12.5");
        assertThat(SheetCells.text(230034L)).isEqualTo("230034");
    }

    @Test
    void blankTextIsNull() {
        assertThat(SheetCells.text(null)).isNull();
        assertThat(SheetCells.text("   ")).isNull();
        assertThat(SheetCells.text(" ")).isNull();
        assertThat(SheetCells.text("  DMK ")).isEqualTo("DMK");
    }

    /** 45133 is what the sheet stores for the date it displays as 26-Jul-23. */
    @Test
    void serialDaysCountFromTheLotusEpoch() {
        assertThat(SheetCells.date(45133)).isEqualTo(LocalDate.of(2023, 7, 26));
        assertThat(SheetCells.date(44927)).isEqualTo(LocalDate.of(2023, 1, 1));
        // The fraction is the time of day and does not move the date.
        assertThat(SheetCells.date(45133.75)).isEqualTo(LocalDate.of(2023, 7, 26));
    }

    @Test
    void datesTypedAsTextStillRead() {
        assertThat(SheetCells.date("26-Jul-23")).isEqualTo(LocalDate.of(2023, 7, 26));
        assertThat(SheetCells.date("26-jul-2023")).isEqualTo(LocalDate.of(2023, 7, 26));
        assertThat(SheetCells.date("2023-07-26")).isEqualTo(LocalDate.of(2023, 7, 26));
        assertThat(SheetCells.date("26/07/2023")).isEqualTo(LocalDate.of(2023, 7, 26));
    }

    @Test
    void aBuddhistEraYearIsConverted() {
        assertThat(SheetCells.date("26/07/2566")).isEqualTo(LocalDate.of(2023, 7, 26));
    }

    @Test
    void anythingElseIsNullNotAGuess() {
        assertThat(SheetCells.date("ข้อมูลอ้างอิง")).isNull();
        assertThat(SheetCells.date("")).isNull();
        assertThat(SheetCells.date(0)).isNull();
        assertThat(SheetCells.date(-5)).isNull();
    }
}
