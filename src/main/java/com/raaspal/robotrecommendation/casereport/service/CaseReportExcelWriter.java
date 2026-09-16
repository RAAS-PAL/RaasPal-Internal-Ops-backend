package com.raaspal.robotrecommendation.casereport.service;

import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.entity.CaseReportDefinition;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a pending-case sheet as the workbook the team used to build by hand.
 *
 * <p>The layout is the one on screen, which was itself laid out to match the file the
 * team sends: one row per case, the same column order, the SLA column reading
 * {@code over SLA} / {@code Within SLA} / {@code On Hold} verbatim. Which of the two
 * site columns appears depends on the sheet - MK prints both Project and Branch,
 * Cleaning only Project, Makro only Branch - because that is what each recipient's
 * copy has always shown, and the frontend's {@code CASE_REPORTS} spec makes the same
 * choice for the table.
 *
 * <p>Dates and day counts are written as real dates and numbers, not text, so the
 * recipient can sort and filter without retyping. Over-SLA rows are tinted red the
 * way the live workbook tints them; On Hold amber; the rest are left alone.
 */
@Component
public class CaseReportExcelWriter {

    private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy");
    private static final String DATE_FORMAT = "yyyy-mm-dd";

    /** Excel column widths are in 1/256ths of a character. */
    private static final int CHAR = 256;

    public byte[] write(CaseReportDefinition definition, LocalDate asOf, List<CaseReportRow> rows) {
        List<Column> columns = columnsFor(definition.getCode());

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            Sheet sheet = workbook.createSheet(sheetName(definition));

            // Title row, merged across the table: which sheet, and the date it is for.
            Row title = sheet.createRow(0);
            title.setHeightInPoints(22);
            Cell titleCell = title.createCell(0);
            titleCell.setCellValue(definition.getName() + " — " + TITLE_DATE.format(asOf));
            titleCell.setCellStyle(styles.title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, columns.size() - 1));

            Row header = sheet.createRow(2);
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(columns.get(c).header);
                cell.setCellStyle(styles.header);
            }

            int r = 3;
            for (CaseReportRow row : rows) {
                Row out = sheet.createRow(r++);
                // The tint marks the SLA cell, not the row. Colouring all eleven cells
                // put the Thai problem and solution prose on saturated pink, which is
                // the hardest part of the sheet to read and the part people actually
                // read; the status belongs to the SLA column, so the colour lives there.
                CellStyle slaText = slaTint(styles, row);
                for (int c = 0; c < columns.size(); c++) {
                    Column col = columns.get(c);
                    col.write(out.createCell(c), row,
                            col.tinted() ? slaText : styles.text, styles.wrap, styles.date, styles.number);
                }
            }

            for (int c = 0; c < columns.size(); c++) {
                sheet.setColumnWidth(c, columns.get(c).width * CHAR);
            }
            // Title and header stay put while the recipient scrolls a long sheet.
            sheet.createFreezePane(0, 3);
            if (!rows.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(2, 2 + rows.size(), 0, columns.size() - 1));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            // Writing to memory; the only way this throws is a POI internal fault.
            throw new UncheckedIOException("Could not build the " + definition.getCode() + " workbook", e);
        }
    }

    /**
     * The download name: {@code mk-pending-2026-09-14.xlsx}. Lower-case with hyphens so
     * it survives every mail client and file system the team sends it through.
     */
    public String filename(CaseReportDefinition definition, LocalDate asOf) {
        return definition.getCode().toLowerCase().replace('_', '-') + "-" + asOf + ".xlsx";
    }

    /** Excel caps sheet names at 31 characters and bans a handful of punctuation. */
    private static String sheetName(CaseReportDefinition definition) {
        String name = definition.getName().replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        return name.length() <= 31 ? name : name.substring(0, 31).trim();
    }

    /**
     * A cleaning case can name several robots, typed into one board field as
     * "A, B, C". Written one per line so the cell wraps between serials rather than
     * through one, which is what a 22-character column would otherwise do.
     */
    static String oneSerialPerLine(String serialNumber) {
        if (serialNumber == null) return null;
        return java.util.Arrays.stream(serialNumber.split("\\s*[,\\n]\\s*"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * Red over SLA, green within it, amber on hold - the same three the console uses.
     * Unknown stays uncoloured on purpose: see {@link SlaStatus}, where a blank cell is
     * the honest answer rather than a green one nobody can stand behind.
     */
    private static CellStyle slaTint(Styles styles, CaseReportRow row) {
        if (row.sla() == null) return styles.text;
        return switch (row.sla()) {
            case BREACHED -> styles.textBreached;
            case WITHIN -> styles.textWithin;
            case ON_HOLD -> styles.textOnHold;
            case UNKNOWN -> styles.text;
        };
    }

    private static List<Column> columnsFor(String code) {
        if (CaseReportDefinition.AOTGA_PENDING.equals(code)) {
            return aotgaColumns();
        }
        boolean project = !CaseReportDefinition.MAKRO_PENDING.equals(code);
        boolean branch = !CaseReportDefinition.CLEANING_PENDING.equals(code);

        List<Column> columns = new ArrayList<>();
        columns.add(Column.number("No", 6, r -> (double) r.no()));
        if (project) columns.add(Column.text("Project", 14, CaseReportRow::project));
        if (branch) columns.add(Column.text("Branch", 22, CaseReportRow::branch));
        columns.add(Column.text("Robot", 12, CaseReportRow::robot));
        columns.add(Column.wrap("SN", 22, r -> oneSerialPerLine(r.serialNumber())));
        columns.add(Column.wrap("Problem", 34, CaseReportRow::problem));
        columns.add(Column.wrap("Solution", 48, CaseReportRow::solution));
        columns.add(Column.date("Open Date", 13, CaseReportRow::openDate));
        columns.add(Column.date("RE On Site", 13, CaseReportRow::reOnSite));
        columns.add(Column.number("Days", 7, r -> r.days() == null ? null : r.days().doubleValue()));
        columns.add(Column.tintedText("SLA", 12, CaseReportRow::slaLabel));
        return columns;
    }

    /**
     * The {@code RAW_AOTGA} layout, in the RE team's column order. No Solution, RE On
     * Site or SLA; five part-tracking columns instead. Nothing here takes the SLA tint —
     * the sheet does not judge the case, it tracks the part.
     */
    private static List<Column> aotgaColumns() {
        List<Column> columns = new ArrayList<>();
        columns.add(Column.number("No", 6, r -> (double) r.no()));
        columns.add(Column.text("Project", 14, CaseReportRow::project));
        columns.add(Column.text("Robot", 8, CaseReportRow::robot));
        columns.add(Column.wrap("SN", 22, r -> oneSerialPerLine(r.serialNumber())));
        columns.add(Column.wrap("Problem", 30, CaseReportRow::problem));
        columns.add(Column.wrap("Required Part", 26, CaseReportRow::requiredPart));
        columns.add(Column.wrap("Waiting", 30, CaseReportRow::waiting));
        columns.add(Column.text("Waiting From", 16, CaseReportRow::waitingFrom));
        columns.add(Column.date("Open Date", 13, CaseReportRow::openDate));
        columns.add(Column.number("Days", 7, r -> r.days() == null ? null : r.days().doubleValue()));
        columns.add(Column.date("Part Received", 14, CaseReportRow::partReceived));
        columns.add(Column.number("Aging After Received", 12,
                r -> r.agingAfterReceived() == null ? null : r.agingAfterReceived().doubleValue()));
        return columns;
    }

    /**
     * One column: its header, width, whether it carries the SLA tint, and how a row's
     * value lands in the cell.
     */
    private record Column(String header, int width, Kind kind, boolean tinted,
                          java.util.function.Function<CaseReportRow, Object> value) {

        enum Kind { TEXT, WRAP, DATE, NUMBER }

        static Column text(String header, int width, java.util.function.Function<CaseReportRow, String> value) {
            return new Column(header, width, Kind.TEXT, false, value::apply);
        }

        /** Text that takes the row's SLA colour. Only the SLA column does. */
        static Column tintedText(String header, int width, java.util.function.Function<CaseReportRow, String> value) {
            return new Column(header, width, Kind.TEXT, true, value::apply);
        }

        static Column wrap(String header, int width, java.util.function.Function<CaseReportRow, String> value) {
            return new Column(header, width, Kind.WRAP, false, value::apply);
        }

        static Column date(String header, int width, java.util.function.Function<CaseReportRow, LocalDate> value) {
            return new Column(header, width, Kind.DATE, false, value::apply);
        }

        static Column number(String header, int width, java.util.function.Function<CaseReportRow, Double> value) {
            return new Column(header, width, Kind.NUMBER, false, value::apply);
        }

        void write(Cell cell, CaseReportRow row, CellStyle text, CellStyle wrap, CellStyle date, CellStyle number) {
            Object v = value.apply(row);
            switch (kind) {
                case TEXT -> { cell.setCellStyle(text); if (v != null) cell.setCellValue((String) v); }
                case WRAP -> { cell.setCellStyle(wrap); if (v != null) cell.setCellValue((String) v); }
                case DATE -> { cell.setCellStyle(date); if (v != null) cell.setCellValue((LocalDate) v); }
                case NUMBER -> { cell.setCellStyle(number); if (v != null) cell.setCellValue((Double) v); }
            }
        }
    }

    /**
     * Every style the sheet uses, built once per workbook. POI limits a workbook to
     * ~64k styles and each {@code createCellStyle} counts, so styles are never made
     * per cell; the two tinted variants exist because a fill cannot be layered onto a
     * style after the fact. Only text is tinted - the SLA column is the only one that
     * carries the colour.
     */
    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle text;
        final CellStyle wrap;
        final CellStyle date;
        final CellStyle number;
        final CellStyle textBreached;
        final CellStyle textWithin;
        final CellStyle textOnHold;

        Styles(XSSFWorkbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);

            title = wb.createCellStyle();
            title.setFont(titleFont);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            header = base(wb);
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);

            short dateFmt = wb.createDataFormat().getFormat(DATE_FORMAT);

            text = base(wb);
            wrap = base(wb);
            wrap.setWrapText(true);
            date = base(wb);
            date.setDataFormat(dateFmt);
            date.setAlignment(HorizontalAlignment.CENTER);
            number = base(wb);
            number.setAlignment(HorizontalAlignment.RIGHT);

            textBreached = tinted(wb, text, IndexedColors.ROSE);
            textWithin = tinted(wb, text, IndexedColors.LIGHT_GREEN);
            textOnHold = tinted(wb, text, IndexedColors.LEMON_CHIFFON);
        }

        private static CellStyle base(XSSFWorkbook wb) {
            CellStyle s = wb.createCellStyle();
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            s.setVerticalAlignment(VerticalAlignment.TOP);
            return s;
        }

        private static CellStyle tinted(XSSFWorkbook wb, CellStyle from, IndexedColors colour) {
            CellStyle s = wb.createCellStyle();
            s.cloneStyleFrom(from);
            s.setFillForegroundColor(colour.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return s;
        }
    }
}
