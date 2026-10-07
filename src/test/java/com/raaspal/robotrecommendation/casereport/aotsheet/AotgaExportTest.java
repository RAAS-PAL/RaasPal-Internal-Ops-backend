package com.raaspal.robotrecommendation.casereport.aotsheet;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The AOT tab's order and its Excel: the same period, the same order, the same cases apart. Made-up data. */
class AotgaExportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    private static final List<AotgaTracker.Case> CASES = List.of(
            aCase("T-1", "BKK", LocalDate.of(2026, 9, 1), AotgaTracker.Stage.OLD_PART_BACK, 2),
            aCase("T-2", "dmk", LocalDate.of(2026, 10, 1), AotgaTracker.Stage.PART_REQUESTED, 3),
            aCase("T-3", null, null, AotgaTracker.Stage.CLAIMED, 4),
            aCase("T-4", "HKT", LocalDate.of(2026, 9, 15), AotgaTracker.Stage.CLAIMED, 5));

    @Test
    void theTabOpensOnTheNewestIssueDateWithUndatedCasesLast() {
        assertThat(sorted(AotgaSort.of(null, null))).containsExactly("T-2", "T-4", "T-1", "T-3");
        assertThat(sorted(AotgaSort.of("issued", "asc"))).containsExactly("T-1", "T-4", "T-2", "T-3");
    }

    /** Text sorts lower-cased; a case with no value goes last whichever way round. */
    @Test
    void anyColumnSortsBothWaysWithBlanksLast() {
        assertThat(sorted(AotgaSort.of("site", "asc"))).containsExactly("T-1", "T-2", "T-4", "T-3");
        assertThat(sorted(AotgaSort.of("site", "desc"))).containsExactly("T-4", "T-2", "T-1", "T-3");
        assertThat(sorted(AotgaSort.of("stage", "desc"))).containsExactly("T-3", "T-4", "T-1", "T-2");
        // Sent first, then not yet; a case whose old part is not back has nothing to sort by.
        assertThat(sorted(AotgaSort.of("sent", "desc"))).containsExactly("T-3", "T-4", "T-1", "T-2");
        assertThat(sorted(AotgaSort.of("nonsense", "asc"))).isEqualTo(sorted(AotgaSort.DEFAULT));
    }

    @Test
    void theWorkbookNamesThePeriodAndKeepsTheTabsOrder() throws IOException {
        var view = new AotgaTracker.View(true, TODAY, true, TODAY, LocalDateTime.of(2026, 10, 6, 9, 0), CASES,
                List.of(aCase(null, "BKK", null, AotgaTracker.Stage.REPORTED, 9)),
                List.of(aCase("T-9", "BKK", null, AotgaTracker.Stage.OLD_PART_BACK, 7),
                        aCase("T-9", "BKK", null, AotgaTracker.Stage.OLD_PART_BACK, 8)),
                null);
        var period = AotgaController.period("ALL", null, null, TODAY);

        byte[] bytes = new AotgaExcelWriter().write(view, period, AotgaSort.DEFAULT);

        assertThat(period.fileName(TODAY)).isEqualTo("AOTGA_All_2026-10-06");
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet main = book.getSheet("AOTGA");
            assertThat(main.getRow(0).getCell(0).getStringCellValue()).isEqualTo("AOTGA cases as of Tue 6 Oct 2026");
            assertThat(main.getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("All time · every case · sorted by Issue Date, newest first");
            assertThat(column(main, 4)).containsExactly("T-2", "T-4", "T-1");
            assertThat(main.getRow(4).getCell(8).getStringCellValue()).isEqualTo("Sent to Manufacturer");
            assertThat(main.getRow(6).getCell(8).getStringCellValue()).isEqualTo("Sent");
            assertThat(main.getRow(7).getCell(8).getStringCellValue()).isEqualTo("Not yet");
            assertThat(column(book.getSheet("No issue date"), 3)).containsExactly("T-3");
            assertThat(book.getSheet("No ticket number").getRow(4).getCell(0).getNumericCellValue()).isEqualTo(9.0);
            assertThat(book.getSheet("Same ticket number").getRow(4).getCell(1).getStringCellValue()).isEqualTo("T-9");
            assertThat(book.getSheet("Same ticket number").getLastRowNum()).isEqualTo(5);
        }
    }

    /**
     * A week, a month or a day holds the cases issued in it, not every case: September's
     * workbook has September's cases, and the undated ones are left to all time. A day with
     * no copy says the stages are today's.
     */
    @Test
    void aPeriodHoldsTheCasesIssuedInIt() throws IOException {
        var view = new AotgaTracker.View(true, TODAY, true, TODAY, LocalDateTime.of(2026, 10, 6, 9, 0), CASES,
                List.of(), List.of(), LocalDate.of(2026, 9, 30));
        var september = AotgaController.period("MONTHLY", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), TODAY);

        byte[] bytes = new AotgaExcelWriter().write(view, september, AotgaSort.DEFAULT);

        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet main = book.getSheet("AOTGA");
            assertThat(main.getRow(0).getCell(0).getStringCellValue()).isEqualTo(
                    "AOTGA cases as of Tue 6 Oct 2026 (no copy was kept for Wed 30 Sep 2026, so as they stand today)");
            assertThat(main.getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("Monthly · cases issued Tue 1 Sep 2026 – Wed 30 Sep 2026 · sorted by Issue Date, newest first");
            assertThat(main.getRow(2).getCell(0).getStringCellValue())
                    .isEqualTo("2 cases; the 1 case with no issue date is only in the All time export");
            assertThat(column(main, 4)).containsExactly("T-4", "T-1");
            assertThat(book.getSheet("No issue date")).isNull();
        }
    }

    @Test
    void aMissingOrBackwardsPeriodIsTheDayItself() {
        assertThat(AotgaController.period(null, null, null, TODAY).fileName(TODAY)).isEqualTo("AOTGA_2026-10-06");
        assertThat(AotgaController.period("DAILY", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 2), TODAY))
                .isEqualTo(AotgaExcelWriter.Period.day(LocalDate.of(2026, 10, 2)));
        assertThat(AotgaController.period("MONTHLY", LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1), TODAY))
                .isEqualTo(AotgaExcelWriter.Period.day(TODAY));
        assertThat(AotgaController.period("MONTHLY", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), TODAY)
                .fileName(TODAY)).isEqualTo("AOTGA_Monthly_2026-10");
    }

    private static List<String> sorted(AotgaSort sort) {
        return CASES.stream().sorted(sort.comparator()).map(AotgaTracker.Case::ticketNo).toList();
    }

    /** The first column below a heading of {@code headRow} lines. */
    private static List<String> column(Sheet sheet, int headRow) {
        List<String> values = new ArrayList<>();
        for (int r = headRow + 1; r <= sheet.getLastRowNum(); r++) {
            values.add(sheet.getRow(r).getCell(0).getStringCellValue());
        }
        return values;
    }

    private static AotgaTracker.Case aCase(String ticket, String site, LocalDate issued, AotgaTracker.Stage stage, int row) {
        Integer days = issued == null ? null : (int) (TODAY.toEpochDay() - issued.toEpochDay());
        LocalDate claimed = stage == AotgaTracker.Stage.CLAIMED ? TODAY : null;
        return new AotgaTracker.Case(ticket, site, "M40", "SN-" + row, "Problem", issued, days, null, "#ffffff", null,
                stage, null, null, null, claimed, null, null, row, false);
    }
}
