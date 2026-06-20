package com.raaspal.robotrecommendation.report.generators.gausium;

import com.raaspal.robotrecommendation.report.core.ReportGenerator;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * {@link ReportGenerator} for the Gausium brand. Produces a "task-queue-list"
 * sheet matching the layout of {@code docs/Cleaning plan_*.xlsx}: 28 columns
 * (A-AB), a SimSun 14pt bold header on a Grey25% fill with a thin border box,
 * and plain Calibri 11 body rows (no alternating colors, no freeze panes).
 *
 * <p>Columns "Receive task report time", "Task start mode" and "Remarks" are
 * left blank - Gausium's public Task Reports API does not expose them (the
 * reference file appears to be exported from Gausium's own cloud dashboard,
 * which has additional fields). "Task type" is filled with the raw
 * {@code cleaningMode} value, and "Plan running time (s)" reuses the task's
 * working time (same source as "Total time", different format). "Task status"
 * maps the API's {@code taskEndStatus} code to the supplier's label
 * (0 Normal completion, 1 Manual termination, 2 Abnormal termination,
 * 3 Startup failure); unknown/missing codes render blank.
 */
@Component
public class GausiumReportGenerator implements ReportGenerator {

    private static final String BRAND = "GAUSIUM";
    private static final String SHEET_NAME = "task-queue-list";

    private static final String[] HEADERS = {
            "Task start time", "End time", "Map name", "Cleaning plan", "Robot name", "S/N",
            "Task completion (%)", "Cleaning plan area (㎡)", "Total time", "Total time (h)",
            "Actual cleaning area(㎡)", "Work efficiency (㎡/h)", "Water usage (L)",
            "Start battery level (%)", "End battery level (%)", "Brush (%)", "Filter (%)", "Squeegee(%)",
            "Planned crystallization area (㎡)", "Actual crystallization area (㎡)",
            "Receive task report time", "Task start mode", "Uncleaned area (㎡)",
            "Plan running time (s)", "Task type", "Remarks", "Download link", "Task status"
    };

    /** Column widths in characters, matching docs/Cleaning plan_*.xlsx (column U is hidden). */
    private static final int[] COLUMN_WIDTHS = {
            22, 22, 19, 15, 15, 30, 21, 26, 21, 21, 26, 30, 21, 24, 24, 15, 10, 14, 26, 26, 0, 12, 26, 12, 12, 12, 100, 12
    };

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    @Override
    public String getBrand() {
        return BRAND;
    }

    @Override
    public boolean supports(String brand) {
        return BRAND.equalsIgnoreCase(brand);
    }

    @Override
    public byte[] generate(List<RobotTaskReport> reports) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);

            for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
                sheet.setColumnWidth(i, COLUMN_WIDTHS[i] * 256);
            }

            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle bodyStyle = createBodyStyle(workbook);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (RobotTaskReport report : reports) {
                writeRow(sheet.createRow(rowIndex++), report, bodyStyle);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void writeRow(Row row, RobotTaskReport report, CellStyle bodyStyle) {
        String[] values = {
                formatInstant(report.getStartTime()),
                formatInstant(report.getEndTime()),
                nullToEmpty(report.getMapName()),
                nullToEmpty(report.getCleaningPlan()),
                nullToEmpty(report.getRobotUnit().getName()),
                nullToEmpty(report.getRobotUnit().getSerialNumber()),
                formatDecimal(report.getTaskCompletionPct(), 2),
                formatDecimal(report.getPlannedAreaSqm(), 2),
                formatDuration(report.getWorkingTimeSeconds()),
                formatDurationHours(report.getWorkingTimeSeconds()),
                formatDecimal(report.getCleaningAreaSqm(), 2),
                formatDecimal(report.getWorkEfficiencySqmH(), 2),
                formatDecimal(report.getWaterConsumptionL(), 3),
                formatInteger(report.getStartBatteryPct()),
                formatInteger(report.getEndBatteryPct()),
                formatDecimal(report.getBrushResidualPct(), 2),
                formatDecimal(report.getFilterResidualPct(), 2),
                formatDecimal(report.getSuctionBladeResidualPct(), 2),
                formatDecimal(report.getPlannedPolishingAreaSqm(), 2),
                formatDecimal(report.getActualPolishingAreaSqm(), 2),
                "", // Receive task report time - not available from Gausium API
                "", // Task start mode - not available from Gausium API
                formatUncleanedArea(report.getPlannedAreaSqm(), report.getCleaningAreaSqm()),
                formatClock(report.getWorkingTimeSeconds()),
                nullToEmpty(report.getCleaningMode()),
                "", // Remarks - not available from Gausium API
                nullToEmpty(report.getTaskReportPngUri()),
                formatTaskStatus(report.getTaskEndStatus()),
        };

        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(values[i]);
            cell.setCellStyle(bodyStyle);
        }
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        Font headerFont = workbook.createFont();
        headerFont.setFontName("SimSun");
        headerFont.setFontHeightInPoints((short) 14);
        headerFont.setBold(true);

        CellStyle style = workbook.createCellStyle();
        style.setFont(headerFont);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setLocked(true);
        return style;
    }

    private CellStyle createBodyStyle(Workbook workbook) {
        Font bodyFont = workbook.createFont();
        bodyFont.setFontName("Calibri");
        bodyFont.setFontHeightInPoints((short) 11);

        CellStyle style = workbook.createCellStyle();
        style.setFont(bodyFont);
        return style;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Maps Gausium's {@code taskEndStatus} code to the supplier's label.
     * Unknown or missing codes render blank rather than a bare number.
     */
    private static String formatTaskStatus(Integer code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case 0 -> "Normal completion";
            case 1 -> "Manual termination";
            case 2 -> "Abnormal termination";
            case 3 -> "Startup failure";
            default -> "";
        };
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? "" : DATE_TIME_FORMAT.format(instant);
    }

    private static String formatDecimal(BigDecimal value, int scale) {
        return value == null ? "" : value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatInteger(Integer value) {
        return value == null ? "" : value.toString();
    }

    /** "Total time" as "H h M min S sec". */
    private static String formatDuration(Integer totalSeconds) {
        if (totalSeconds == null) {
            return "";
        }
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        return hours + " h " + minutes + " min " + seconds + " sec";
    }

    /** "Total time (h)" as a decimal number of hours, e.g. 0.2019. */
    private static String formatDurationHours(Integer totalSeconds) {
        if (totalSeconds == null) {
            return "";
        }
        return BigDecimal.valueOf(totalSeconds)
                .divide(BigDecimal.valueOf(3600), 4, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** "Plan running time (s)" as "HH: MM: SS", reusing the working time. */
    private static String formatClock(Integer totalSeconds) {
        if (totalSeconds == null) {
            return "";
        }
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        return String.format("%02d: %02d: %02d", hours, minutes, seconds);
    }

    /** "Uncleaned area (sqm)" = planned cleaning area minus actual cleaning area. */
    private static String formatUncleanedArea(BigDecimal planned, BigDecimal actual) {
        if (planned == null || actual == null) {
            return "";
        }
        return planned.subtract(actual).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
