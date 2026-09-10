package com.raaspal.robotrecommendation.kpi;

import com.raaspal.robotrecommendation.kpi.config.KpiCsatProperties;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbookParser;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbookSource;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.CmCounts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.InstallCounts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.MonthMetrics;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.Segment;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse;
import com.raaspal.robotrecommendation.kpi.export.KpiCaseXlsxExporter;
import com.raaspal.robotrecommendation.kpi.export.KpiCsatXlsxExporter;
import com.raaspal.robotrecommendation.kpi.service.KpiCsatService;
import com.raaspal.robotrecommendation.kpi.CsatWorkbookFixtures.MonthSpec;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exports exist to be charted in PowerPoint, so what is asserted here is
 * what makes that work: the header on row 1 with no banner above it, rates as
 * real percentage cells, and a gap left as a gap.
 */
class KpiXlsxExportTest {

    private static final YearMonth JAN = YearMonth.of(2026, 1);

    /** A source holding whatever bytes the test hands it. */
    private static CsatWorkbookSource source(Map<String, byte[]> files) {
        return new CsatWorkbookSource() {
            @Override
            public String describe() {
                return "test folder";
            }

            @Override
            public List<WorkbookFile> list() {
                return files.entrySet().stream()
                        .map(e -> new WorkbookFile(e.getKey(), e.getValue().length,
                                Instant.parse("2026-09-01T00:00:00Z"),
                                () -> new ByteArrayInputStream(e.getValue())))
                        .toList();
            }
        };
    }

    /**
     * Two surveys in one month: PM at 90 of 100 ratings, installation at 1 of 2.
     * Each survey's own figure is its sheet's cell; overall has to be combined,
     * so one response covers both kinds of figure the export distinguishes.
     */
    private static KpiCsatResponse csat() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("ma.xlsx", CsatWorkbookFixtures.workbook("Post-MA CSAT Survey",
                MonthSpec.of("Jan 2026", 200, 100, new int[]{90, 10, 0, 0, 0})));
        files.put("install.xlsx", CsatWorkbookFixtures.workbook("Post-installation CSAT Survey",
                MonthSpec.of("Jan 2026", 4, 2, new int[]{1, 1, 0, 0, 0})));
        return new KpiCsatService(source(files), new CsatWorkbookParser(), new KpiCsatProperties())
                .monthly(JAN, JAN);
    }

    private static Cell cell(Workbook book, String sheet, int row, int column) {
        Sheet s = book.getSheet(sheet);
        assertThat(s).as("sheet '%s'", sheet).isNotNull();
        Row r = s.getRow(row);
        return r == null ? null : r.getCell(column);
    }

    private static List<String> headers(Workbook book, String sheet) {
        List<String> out = new ArrayList<>();
        for (Cell c : book.getSheet(sheet).getRow(0)) {
            out.add(c.getStringCellValue());
        }
        return out;
    }

    private static Workbook read(byte[] bytes) throws IOException {
        return WorkbookFactory.create(new ByteArrayInputStream(bytes));
    }

    @Test
    void csatTopBoxIsMonthsDownAndSurveysAcross() throws IOException {
        try (Workbook book = read(new KpiCsatXlsxExporter().export(csat()))) {
            assertThat(headers(book, "Top Box"))
                    .containsExactly("Month", "Overall", "Installation", "PM", "CM Delivery", "CM Cleaning");
            assertThat(cell(book, "Top Box", 1, 0).getStringCellValue()).isEqualTo("Jan 2026");
            assertThat(cell(book, "Top Box", 1, 1).getNumericCellValue()).isEqualTo(0.892);  // 91 of 102
            assertThat(cell(book, "Top Box", 1, 3).getNumericCellValue()).isEqualTo(0.90);
        }
    }

    /**
     * A percentage cell, not a number that happens to be 0.9 — this is what puts
     * a % axis on the chart instead of one running 0 to 1.
     */
    @Test
    void ratesAreRealPercentageCells() throws IOException {
        try (Workbook book = read(new KpiCsatXlsxExporter().export(csat()))) {
            assertThat(cell(book, "Top Box", 1, 3).getCellStyle().getDataFormatString()).isEqualTo("0.0%");
        }
    }

    /** A survey nobody ran is a gap in the chart. A zero would draw a bar saying nobody was happy. */
    @Test
    void aSurveyThatDidNotRunLeavesTheCellEmpty() throws IOException {
        try (Workbook book = read(new KpiCsatXlsxExporter().export(csat()))) {
            Cell cleaning = cell(book, "Top Box", 1, 5);
            assertThat(cleaning == null || cleaning.getCellType() == CellType.BLANK).isTrue();
            assertThat(cell(book, "By Month and Survey", 5, 3).getStringCellValue()).isEqualTo("not surveyed");
        }
    }

    /** The distinction the whole feature turns on, carried into the file. */
    @Test
    void everyFigureSaysWhetherItWasReadOrCombined() throws IOException {
        try (Workbook book = read(new KpiCsatXlsxExporter().export(csat()))) {
            assertThat(headers(book, "By Month and Survey")).contains("Read from");
            // Rows run overall, installation, PM, delivery, cleaning.
            assertThat(cell(book, "By Month and Survey", 1, 3).getStringCellValue()).isEqualTo("pooled");
            assertThat(cell(book, "By Month and Survey", 2, 3).getStringCellValue()).isEqualTo("sheet cell");
            assertThat(cell(book, "By Month and Survey", 3, 3).getStringCellValue()).isEqualTo("sheet cell");
            assertThat(cell(book, "Period Totals", 1, 2).getStringCellValue()).isEqualTo("pooled");
            assertThat(cell(book, "Period Totals", 2, 2).getStringCellValue()).isEqualTo("sheet cell");
        }
    }

    /** Provenance belongs on its own sheet, because a note above a table breaks Insert Chart. */
    @Test
    void provenanceIsOnItsOwnSheetAndTheChartSheetsStartAtRowOne() throws IOException {
        try (Workbook book = read(new KpiCsatXlsxExporter().export(csat()))) {
            for (String sheet : List.of("Top Box", "Responses", "Response Rate")) {
                assertThat(cell(book, sheet, 0, 0).getStringCellValue()).as(sheet).isEqualTo("Month");
            }
            StringBuilder about = new StringBuilder();
            for (Row row : book.getSheet("About")) {
                for (Cell c : row) {
                    if (c.getCellType() == CellType.STRING) {
                        about.append(c.getStringCellValue()).append('\n');
                    }
                }
            }
            assertThat(about.toString())
                    .contains("ma.xlsx")
                    .contains("Jan 2026")
                    .contains("Top Box cell");
        }
    }

    private static Segment segment(int installs, int installFirstTime, int cases, int within, int over) {
        Double installRate = installs == 0 ? null : Math.round(installFirstTime * 1000.0 / installs) / 10.0;
        Double slaRate = within + over == 0 ? null : Math.round(within * 1000.0 / (within + over)) / 10.0;
        return new Segment(
                new InstallCounts(installs, installFirstTime, installs - installFirstTime, 0, installRate),
                new CmCounts(cases, cases, 0, 0, within, over, 0, cases == 0 ? null : 100.0, slaRate));
    }

    private static KpiCaseMetricsResponse metrics() {
        Map<String, String> definitions = new LinkedHashMap<>();
        definitions.put("sla", "Open Date to RE Action date, against the board's limit.");
        return new KpiCaseMetricsResponse("2026-01", "2026-02",
                List.of(new MonthMetrics("2026-01", segment(10, 8, 100, 70, 30), segment(6, 5, 60, 40, 20),
                                segment(4, 3, 40, 30, 10)),
                        new MonthMetrics("2026-02", segment(0, 0, 0, 0, 0), segment(0, 0, 0, 0, 0),
                                segment(0, 0, 0, 0, 0))),
                new KpiCaseMetricsResponse.Totals(segment(10, 8, 100, 70, 30), segment(6, 5, 60, 40, 20),
                        segment(4, 3, 40, 30, 10)),
                100, 0, 5, LocalDateTime.parse("2026-09-10T09:00:00"), 7, 30, 3, true, definitions);
    }

    @Test
    void theReportGetsOneSheetPerPanelAndNamesWhatIsMissing() throws IOException {
        try (Workbook book = read(new KpiCaseXlsxExporter().export(metrics()))) {
            List<String> sheets = new ArrayList<>();
            book.forEach(s -> sheets.add(s.getSheetName()));
            assertThat(sheets).containsExactly("1st Time Install", "CM Cases", "First Time Fix", "SLA",
                    "By Service Line", "Period Totals", "About");

            assertThat(cell(book, "1st Time Install", 1, 1).getNumericCellValue()).isEqualTo(0.80);
            assertThat(cell(book, "CM Cases", 1, 3).getNumericCellValue()).isEqualTo(100);
            assertThat(cell(book, "SLA", 1, 1).getNumericCellValue()).isEqualTo(0.70);

            // An empty month has no rate to show, and must not claim 0%.
            Cell emptyMonth = cell(book, "1st Time Install", 2, 1);
            assertThat(emptyMonth == null || emptyMonth.getCellType() == CellType.BLANK).isTrue();

            StringBuilder about = new StringBuilder();
            for (Row row : book.getSheet("About")) {
                for (Cell c : row) {
                    if (c.getCellType() == CellType.STRING) {
                        about.append(c.getStringCellValue()).append('\n');
                    }
                }
            }
            assertThat(about.toString())
                    .contains("PM Complete")
                    .contains("has its own export")
                    .contains("3 days")
                    .contains("RE Action date");
        }
    }
}
