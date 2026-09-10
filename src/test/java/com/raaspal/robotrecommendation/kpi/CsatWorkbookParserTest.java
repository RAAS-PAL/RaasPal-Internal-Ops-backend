package com.raaspal.robotrecommendation.kpi;

import com.raaspal.robotrecommendation.kpi.CsatWorkbookFixtures.MonthSpec;
import com.raaspal.robotrecommendation.kpi.csat.CsatStream;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbook;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbook.MonthAggregate;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbookParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The parser reads each month sheet by label and takes the sheet's Top Box as
 * the RE team wrote it — read and show, never correct. The two column sums it
 * also reads exist only so sheets can be combined the way the deck does.
 */
class CsatWorkbookParserTest {

    private final CsatWorkbookParser parser = new CsatWorkbookParser();

    private CsatWorkbook parse(String fileName, byte[] bytes) throws IOException {
        return parser.parse(fileName, Instant.parse("2026-09-01T00:00:00Z"), new ByteArrayInputStream(bytes));
    }

    /** The MA workbook's April 2026 sheet, figure for figure. */
    @Test
    void readsTheThreeTalliesByLabel() throws IOException {
        byte[] bytes = CsatWorkbookFixtures.workbook("Post-MA CSAT Survey",
                MonthSpec.of("Apr 2026", 16, 3,
                        new int[]{3, 0, 0, 0, 0},
                        new int[]{3, 0, 0, 0, 0},
                        new int[]{2, 0, 1, 0, 0},
                        new int[]{2, 0, 1, 0, 0},
                        new int[]{2, 0, 1, 0, 0}));

        CsatWorkbook wb = parse("Monthly CSAT Survey MA.xlsx", bytes);

        assertThat(wb.stream()).isEqualTo(CsatStream.PM);
        assertThat(wb.warnings()).isEmpty();
        assertThat(wb.months()).containsOnlyKeys(YearMonth.of(2026, 4));
        MonthAggregate april = wb.months().get(YearMonth.of(2026, 4));
        assertThat(april.customers()).isEqualTo(16);
        assertThat(april.responses()).isEqualTo(3);
        assertThat(april.notEvaluated()).isEqualTo(13);
        assertThat(april.topBox()).isCloseTo(0.80, within(1e-9));
        assertThat(april.fives()).isEqualTo(12);
        assertThat(april.ratings()).isEqualTo(15);
    }

    /** The team is not consistent about month names; only the month sheets count. */
    @Test
    void acceptsAbbreviatedAndFullMonthNamesAndIgnoresEverythingElse() throws IOException {
        byte[] bytes = CsatWorkbookFixtures.workbook("Post-installation CSAT Survey",
                MonthSpec.of("Jan 2026", 4, 2, new int[]{2, 0, 0, 0, 0}),
                MonthSpec.of("June 2026", 4, 2, new int[]{2, 0, 0, 0, 0}),
                MonthSpec.of("August 2026", 4, 2, new int[]{2, 0, 0, 0, 0}),
                MonthSpec.of("Sept. 2026", 4, 2, new int[]{2, 0, 0, 0, 0}));

        CsatWorkbook wb = parse("installation.xlsx", bytes);

        assertThat(wb.stream()).isEqualTo(CsatStream.INSTALLATION);
        assertThat(wb.months()).containsOnlyKeys(
                YearMonth.of(2026, 1), YearMonth.of(2026, 6), YearMonth.of(2026, 8), YearMonth.of(2026, 9));
    }

    /**
     * The real installation workbook's March 2026 sheet: someone answered only
     * some questions, and the sheet's Top Box cell (83.3%) is not what the
     * rating table pools to (9 of 11 = 81.8%). The cell is the RE team's number
     * and it is the one kept — no recomputation, no warning. The sums are kept
     * beside it, untouched, for combining sheets later.
     */
    @Test
    void takesTheSheetsTopBoxCellAsItIs() throws IOException {
        byte[] bytes = CsatWorkbookFixtures.workbook("Post-installation CSAT Survey",
                MonthSpec.of("Mar 2026", 4, 3,
                        new int[]{2, 1, 0, 0, 0},
                        new int[]{2, 0, 0, 0, 0},
                        new int[]{2, 0, 0, 0, 0},
                        new int[]{2, 1, 0, 0, 0},
                        new int[]{1, 0, 0, 0, 0}).withTopBoxCell(0.833));

        CsatWorkbook wb = parse("installation.xlsx", bytes);

        MonthAggregate march = wb.months().get(YearMonth.of(2026, 3));
        assertThat(march.topBox()).isCloseTo(0.833, within(1e-9));
        assertThat(march.fives()).isEqualTo(9);
        assertThat(march.ratings()).isEqualTo(11);
        assertThat(wb.warnings()).isEmpty();
    }

    @Test
    void tellsTheSurveyFromTheSheetTitleBeforeTheFileName() throws IOException {
        byte[] cleaning = CsatWorkbookFixtures.workbook("Post-Mantaianace Cleaning bot  CSAT Survey",
                MonthSpec.of("Jan 2026", 2, 1, new int[]{1, 0, 0, 0, 0}));
        byte[] delivery = CsatWorkbookFixtures.workbook("Post-Mantaianace delivery bot  CSAT Survey",
                MonthSpec.of("Jan 2026", 2, 1, new int[]{1, 0, 0, 0, 0}));
        byte[] untitled = CsatWorkbookFixtures.workbook("CSAT Survey",
                MonthSpec.of("Jan 2026", 2, 1, new int[]{1, 0, 0, 0, 0}));

        // "Mantaianace" contains "ma": cleaning and delivery must not be read as the MA survey.
        assertThat(parse("a.xlsx", cleaning).stream()).isEqualTo(CsatStream.CM_CLEANING);
        assertThat(parse("b.xlsx", delivery).stream()).isEqualTo(CsatStream.CM_DELIVERY);
        assertThat(parse("Monthly  CSAT Survey MA  as of August 2026 (1).xlsx", untitled).stream()).isEqualTo(CsatStream.PM);
        CsatWorkbook unknown = parse("survey.xlsx", untitled);
        assertThat(unknown.stream()).isNull();
        assertThat(unknown.warnings()).anySatisfy(w -> assertThat(w).contains("cannot tell which survey"));
    }

    @Test
    void aMonthSheetWithoutATopBoxCellIsSkippedWithAWarning() throws IOException {
        byte[] bytes = CsatWorkbookFixtures.workbook("Post-MA CSAT Survey",
                MonthSpec.of("Jan 2026", 2, 1, new int[]{1, 0, 0, 0, 0}));
        // Corrupt February by hand: a month sheet with a title and nothing else.
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            wb.createSheet("Feb 2026").createRow(0).createCell(0).setCellValue("Post-MA CSAT Survey");
            var out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            bytes = out.toByteArray();
        }

        CsatWorkbook parsed = parse("ma.xlsx", bytes);

        assertThat(parsed.months()).containsOnlyKeys(YearMonth.of(2026, 1));
        assertThat(parsed.warnings()).hasSize(1);
        assertThat(parsed.warnings().get(0)).contains("Feb 2026").contains("skipped");
    }
}
