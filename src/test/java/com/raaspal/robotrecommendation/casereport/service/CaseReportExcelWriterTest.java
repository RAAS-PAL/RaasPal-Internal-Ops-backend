package com.raaspal.robotrecommendation.casereport.service;

import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.entity.CaseReportDefinition;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workbook is what the team forwards, so it is read back with POI and checked
 * cell by cell rather than trusted to have been written.
 */
class CaseReportExcelWriterTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 14);

    private final CaseReportExcelWriter writer = new CaseReportExcelWriter();

    private static CaseReportDefinition definition(String code, String name) {
        return CaseReportDefinition.builder().code(code).name(name).build();
    }

    private static CaseReportRow row(int no, String project, String branch, LocalDate open, LocalDate onSite,
                                     Integer days, SlaStatus sla) {
        return new CaseReportRow(no, project, branch, "Pudu 1", "PD9112214736023",
                "แบตลดลงเร็ว", "รอ QT", open, onSite, days, sla, sla == null ? null : sla.label(),
                null, null, null, null, null,
                "กรุงเทพมหานคร", null, "item-" + no, false, false);
    }

    private static Workbook read(byte[] bytes) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private static List<String> headers(Sheet sheet) {
        List<String> out = new ArrayList<>();
        for (Cell cell : sheet.getRow(2)) out.add(cell.getStringCellValue());
        return out;
    }

    @Test
    void mkSheetPrintsBothSiteColumnsInScreenOrder() throws IOException {
        byte[] bytes = writer.write(definition(CaseReportDefinition.MK_PENDING, "MK pending cases"), AS_OF, List.of(
                row(1, "MK", "M154 โลตัส จันทบุรี", LocalDate.of(2026, 8, 15), LocalDate.of(2026, 9, 12), 30, SlaStatus.BREACHED)));

        try (Workbook wb = read(bytes)) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("MK pending cases");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("MK pending cases — 14 September 2026");
            assertThat(headers(sheet)).containsExactly(
                    "No", "Project", "Branch", "Robot", "SN", "Problem", "Solution", "Open Date", "RE On Site", "Days", "SLA");
        }
    }

    @Test
    void cleaningSheetDropsBranchAndMakroDropsProject() throws IOException {
        byte[] cleaning = writer.write(definition(CaseReportDefinition.CLEANING_PENDING, "Cleaning pending"), AS_OF, List.of());
        byte[] makro = writer.write(definition(CaseReportDefinition.MAKRO_PENDING, "Makro pending"), AS_OF, List.of());

        try (Workbook wb = read(cleaning)) {
            assertThat(headers(wb.getSheetAt(0))).startsWith("No", "Project", "Robot").doesNotContain("Branch");
        }
        try (Workbook wb = read(makro)) {
            assertThat(headers(wb.getSheetAt(0))).startsWith("No", "Branch", "Robot").doesNotContain("Project");
        }
    }

    /** Dates and counts are typed cells, so the recipient can sort and filter them. */
    @Test
    void writesDatesAndNumbersAsValuesNotText() throws IOException {
        byte[] bytes = writer.write(definition(CaseReportDefinition.MK_PENDING, "MK pending cases"), AS_OF, List.of(
                row(1, "MK", "M154", LocalDate.of(2026, 8, 15), LocalDate.of(2026, 9, 12), 30, SlaStatus.BREACHED)));

        try (Workbook wb = read(bytes)) {
            Row r = wb.getSheetAt(0).getRow(3);
            assertThat(r.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(r.getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(r.getCell(7).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 15));
            assertThat(r.getCell(8).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 12));
            assertThat(r.getCell(9).getNumericCellValue()).isEqualTo(30.0);
            assertThat(r.getCell(10).getStringCellValue()).isEqualTo("over SLA");
            assertThat(r.getCell(5).getStringCellValue()).isEqualTo("แบตลดลงเร็ว");
        }
    }

    /** A missing date or count must be an empty cell, never "null" or a zero. */
    @Test
    void leavesUnknownValuesBlank() throws IOException {
        byte[] bytes = writer.write(definition(CaseReportDefinition.MK_PENDING, "MK pending cases"), AS_OF, List.of(
                row(1, "MK", null, null, null, null, SlaStatus.UNKNOWN)));

        try (Workbook wb = read(bytes)) {
            Row r = wb.getSheetAt(0).getRow(3);
            assertThat(r.getCell(2).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(r.getCell(7).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(r.getCell(8).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(r.getCell(9).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(r.getCell(10).getStringCellValue()).isEmpty();
            // Unknown is deliberately uncoloured - see SlaStatus.
            assertThat(r.getCell(10).getCellStyle().getFillPattern()).isEqualTo(FillPatternType.NO_FILL);
        }
    }

    /** Index of the SLA column on an MK sheet, which prints both site columns. */
    private static final int SLA = 10;

    @Test
    void tintsTheSlaCellRedAmberOrGreenByStatus() throws IOException {
        byte[] bytes = writer.write(definition(CaseReportDefinition.MK_PENDING, "MK pending cases"), AS_OF, List.of(
                row(1, "MK", "A", AS_OF.minusDays(9), null, 8, SlaStatus.BREACHED),
                row(2, "MK", "B", AS_OF.minusDays(2), null, 1, SlaStatus.ON_HOLD),
                row(3, "MK", "C", AS_OF.minusDays(1), null, 0, SlaStatus.WITHIN)));

        try (Workbook wb = read(bytes)) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(3).getCell(SLA).getCellStyle().getFillForegroundColor())
                    .isEqualTo(IndexedColors.ROSE.getIndex());
            assertThat(sheet.getRow(4).getCell(SLA).getCellStyle().getFillForegroundColor())
                    .isEqualTo(IndexedColors.LEMON_CHIFFON.getIndex());
            assertThat(sheet.getRow(5).getCell(SLA).getCellStyle().getFillForegroundColor())
                    .isEqualTo(IndexedColors.LIGHT_GREEN.getIndex());
        }
    }

    /**
     * The tint marks the status, not the row. A whole pink row put the Thai problem
     * and solution prose on saturated colour - the hardest part of the sheet to read.
     */
    @Test
    void leavesEveryOtherCellOfAnOverSlaRowUncoloured() throws IOException {
        byte[] bytes = writer.write(definition(CaseReportDefinition.MK_PENDING, "MK pending cases"), AS_OF, List.of(
                row(1, "MK", "M154", AS_OF.minusDays(30), AS_OF.minusDays(2), 30, SlaStatus.BREACHED)));

        try (Workbook wb = read(bytes)) {
            Row r = wb.getSheetAt(0).getRow(3);
            for (int c = 0; c < SLA; c++) {
                assertThat(r.getCell(c).getCellStyle().getFillPattern())
                        .as("column %d must not be tinted", c)
                        .isEqualTo(FillPatternType.NO_FILL);
            }
            assertThat(r.getCell(SLA).getCellStyle().getFillForegroundColor())
                    .isEqualTo(IndexedColors.ROSE.getIndex());
        }
    }

    @Test
    void putsEachSerialOnItsOwnLine() {
        assertThat(CaseReportExcelWriter.oneSerialPerLine("GS101-0100-66P-P000, GS101-0100-66P-V000,GS101-0100-66P-T000"))
                .isEqualTo("GS101-0100-66P-P000\nGS101-0100-66P-V000\nGS101-0100-66P-T000");
        assertThat(CaseReportExcelWriter.oneSerialPerLine("GS4016120PAQ3000")).isEqualTo("GS4016120PAQ3000");
        assertThat(CaseReportExcelWriter.oneSerialPerLine(null)).isNull();
    }

    @Test
    void filenameIsLowerCaseHyphenatedWithTheDate() {
        assertThat(writer.filename(definition(CaseReportDefinition.MK_PENDING, "x"), AS_OF)).isEqualTo("mk-pending-2026-09-14.xlsx");
        assertThat(writer.filename(definition(CaseReportDefinition.CLEANING_PENDING, "x"), AS_OF)).isEqualTo("cleaning-pending-2026-09-14.xlsx");
    }

    /** Excel refuses a sheet name over 31 characters or containing certain punctuation. */
    @Test
    void sheetNameSurvivesExcelsRules() throws IOException {
        byte[] bytes = writer.write(
                definition(CaseReportDefinition.MK_PENDING, "MK / Yayoi / Bonus Suki: pending delivery cases [all]"), AS_OF, List.of());
        try (Workbook wb = read(bytes)) {
            String name = wb.getSheetAt(0).getSheetName();
            assertThat(name.length()).isLessThanOrEqualTo(31);
            assertThat(name).doesNotContain("/", ":", "[", "]");
        }
    }
}
