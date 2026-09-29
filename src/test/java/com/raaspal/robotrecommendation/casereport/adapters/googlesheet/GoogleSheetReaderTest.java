package com.raaspal.robotrecommendation.casereport.adapters.googlesheet;

import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.dto.SheetRow;
import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.dto.SheetTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shaping a values payload into headers and rows, the way the API actually returns it. */
class GoogleSheetReaderTest {

    @Test
    void theHeaderRowNamesTheColumnsAndRowsKeepTheirSheetNumber() {
        SheetTable table = GoogleSheetReader.parse(List.of(
                List.of("n", "Issue Date", "S/N"),
                List.of(1, 45133, "GS-TEST-0001"),
                List.of(2, 45134, "GS-TEST-0002")), 1);

        assertThat(table.headers()).containsExactly("n", "Issue Date", "S/N");
        assertThat(table.rows()).extracting(SheetRow::rowNumber).containsExactly(2, 3);
        assertThat(table.rows().get(1).text("S/N")).isEqualTo("GS-TEST-0002");
    }

    /** Hidden columns are the ones nobody labels; their values are still worth keeping. */
    @Test
    void aBlankHeaderIsNamedByItsLetter() {
        SheetTable table = GoogleSheetReader.parse(List.of(
                List.of("n", "", "Name req"),
                List.of(1, "hidden value", "someone")), 1);

        assertThat(table.headers()).containsExactly("n", "Column B", "Name req");
        assertThat(table.rows().get(0).text("Column B")).isEqualTo("hidden value");
    }

    @Test
    void aRepeatedHeaderIsSuffixedSoNeitherColumnIsLost() {
        SheetTable table = GoogleSheetReader.parse(List.of(
                List.of("Pic", "Pic", "pic "),
                List.of("a", "b", "c")), 1);

        assertThat(table.headers()).containsExactly("Pic", "Pic (2)", "pic (3)");
        assertThat(table.rows().get(0).cells()).containsValues("a", "b", "c");
    }

    /** Google leaves trailing empty cells off a row and fills gaps with "". */
    @Test
    void shortRowsAndEmptyRowsAreHandled() {
        SheetTable table = GoogleSheetReader.parse(List.of(
                List.of("n", "Issue Date", "S/N", "Pic 1"),
                List.of(1, "", "GS-TEST-0001"),
                List.of(),
                List.of("", "  "),
                List.of(3)), 1);

        assertThat(table.headers()).hasSize(4);
        assertThat(table.rows()).extracting(SheetRow::rowNumber).containsExactly(2, 5);
        assertThat(table.rows().get(0).cells()).containsOnlyKeys("n", "S/N");
    }

    @Test
    void aHeaderBelowATitleRowIsFoundByItsRowNumber() {
        SheetTable table = GoogleSheetReader.parse(List.of(
                List.of("AOT case log"),
                List.of("n", "S/N"),
                List.of(1, "GS-TEST-0001")), 2);

        assertThat(table.headers()).containsExactly("n", "S/N");
        assertThat(table.rows()).extracting(SheetRow::rowNumber).containsExactly(3);
    }

    @Test
    void headersAreFoundIgnoringCaseAndSpacing() {
        SheetTable table = GoogleSheetReader.parse(List.of(List.of("repair By", "Issue  Date ")), 1);

        assertThat(table.header("Repair by")).contains("repair By");
        assertThat(table.header(" issue date")).contains("Issue Date");
        assertThat(table.header("Status")).isEmpty();
    }

    @Test
    void anEmptyPayloadIsAnEmptyTable() {
        assertThat(GoogleSheetReader.parse(List.of(), 1).rows()).isEmpty();
        assertThat(GoogleSheetReader.parse(null, 1).headers()).isEmpty();
        assertThatThrownBy(() -> GoogleSheetReader.parse(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void columnLettersRollOverLikeTheSheet() {
        assertThat(GoogleSheetReader.columnLetter(0)).isEqualTo("A");
        assertThat(GoogleSheetReader.columnLetter(25)).isEqualTo("Z");
        assertThat(GoogleSheetReader.columnLetter(26)).isEqualTo("AA");
        assertThat(GoogleSheetReader.columnLetter(27)).isEqualTo("AB");
    }
}
