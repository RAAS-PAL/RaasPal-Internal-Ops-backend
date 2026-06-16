package com.raaspal.robotrecommendation.report.generators.gausium;

import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GausiumReportGeneratorTest {

    private final GausiumReportGenerator generator = new GausiumReportGenerator();

    @Test
    void supportsGausiumBrandOnly() {
        assertThat(generator.getBrand()).isEqualTo("GAUSIUM");
        assertThat(generator.supports("gausium")).isTrue();
        assertThat(generator.supports("GAUSIUM")).isTrue();
        assertThat(generator.supports("CVTE")).isFalse();
    }

    @Test
    void generatesTaskQueueListSheetMatchingReferenceFormat() throws IOException {
        RobotTaskReport report = sampleReport();

        byte[] xlsx = generator.generate(List.of(report));

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheet("task-queue-list");
            assertThat(sheet).isNotNull();

            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Task start time");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("Robot name");
            assertThat(header.getCell(27).getStringCellValue()).isEqualTo("Task status");

            CellStyle headerStyle = header.getCell(0).getCellStyle();
            Font headerFont = workbook.getFontAt(headerStyle.getFontIndex());
            assertThat(headerFont.getFontName()).isEqualTo("SimSun");
            assertThat(headerFont.getFontHeightInPoints()).isEqualTo((short) 14);
            assertThat(headerFont.getBold()).isTrue();
            assertThat(headerStyle.getFillForegroundColor()).isEqualTo(IndexedColors.GREY_25_PERCENT.getIndex());
            assertThat(headerStyle.getBorderTop()).isEqualTo(BorderStyle.THIN);

            // Column U ("Receive task report time") is hidden, matching the reference template
            assertThat(sheet.getColumnWidth(20)).isEqualTo(0);
            assertThat(sheet.getColumnWidth(0)).isEqualTo(22 * 256);

            Row data = sheet.getRow(1);
            assertThat(cellString(data, 0)).isEqualTo("2026-05-31 23:00:00");
            assertThat(cellString(data, 1)).isEqualTo("2026-05-31 23:14:56");
            assertThat(cellString(data, 4)).isEqualTo("Site A");
            assertThat(cellString(data, 5)).isEqualTo("GS401-6120-PAQ-B000");
            assertThat(cellString(data, 6)).isEqualTo("86.60");
            assertThat(cellString(data, 8)).isEqualTo("0 h 12 min 7 sec");
            assertThat(cellString(data, 9)).isEqualTo("0.2019");
            assertThat(cellString(data, 15)).isEqualTo("99.92");
            assertThat(cellString(data, 22)).isEqualTo("19.64");
            assertThat(cellString(data, 23)).isEqualTo("00: 12: 07");
            assertThat(cellString(data, 24)).isEqualTo("Floor Washing");
            // Columns with no source from the Gausium API are left blank
            assertThat(cellString(data, 20)).isEmpty();
            assertThat(cellString(data, 21)).isEmpty();
            assertThat(cellString(data, 25)).isEmpty();
            assertThat(cellString(data, 27)).isEmpty();
        }
    }

    private static String cellString(Row row, int columnIndex) {
        return row.getCell(columnIndex).getStringCellValue();
    }

    private static RobotTaskReport sampleReport() {
        RobotUnit robotUnit = RobotUnit.builder()
                .serialNumber("GS401-6120-PAQ-B000")
                .brand("GAUSIUM")
                .name("Site A")
                .build();

        return RobotTaskReport.builder()
                .robotUnit(robotUnit)
                .brand("GAUSIUM")
                .startTime(Instant.parse("2026-05-31T23:00:00Z"))
                .endTime(Instant.parse("2026-05-31T23:14:56Z"))
                .mapName("MCR_Floor-8")
                .cleaningPlan("Zone-1")
                .taskCompletionPct(new BigDecimal("86.60"))
                .plannedAreaSqm(new BigDecimal("146.31"))
                .workingTimeSeconds(727)
                .cleaningAreaSqm(new BigDecimal("126.67"))
                .workEfficiencySqmH(new BigDecimal("626.77"))
                .waterConsumptionL(new BigDecimal("2.289"))
                .startBatteryPct(65)
                .endBatteryPct(59)
                .brushResidualPct(new BigDecimal("99.92"))
                .filterResidualPct(new BigDecimal("100.00"))
                .suctionBladeResidualPct(new BigDecimal("100.00"))
                .cleaningMode("Floor Washing")
                .taskReportPngUri("https://example.com/report.png")
                .build();
    }
}
