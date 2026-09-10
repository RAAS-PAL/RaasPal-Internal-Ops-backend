package com.raaspal.robotrecommendation.kpi.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * A small spreadsheet writer, shaped for charting rather than for reading.
 *
 * <p>Every sheet is one block: the column headers on row 1, the data from row 2,
 * nothing above and nothing merged. That is what Excel's Insert Chart needs to
 * pick the series names up by itself — a title row or a merged banner above the
 * table and it selects the wrong range. The provenance that would otherwise go
 * at the top of each sheet goes on a sheet of its own instead.
 *
 * <p>Each column declares its type once, so a rate is always a real percentage
 * cell (0.792 formatted {@code 0.0%}) and a count is always an integer. Rates
 * arrive from the API as percentages — 79.2 — and are divided here, in one
 * place, rather than at every call site.
 *
 * <p>A null value leaves the cell empty. It must not become 0: a gap in a chart
 * says "not surveyed", a zero bar says "nobody was happy".
 */
final class XlsxBook {

    enum Type { TEXT, COUNT, PERCENT, DECIMAL }

    /**
     * @param chars width in characters — set rather than auto-sized, which needs
     *              font metrics and is a per-JVM lottery on a headless server
     */
    record Column(String header, Type type, int chars) {
        static Column text(String header, int chars) {
            return new Column(header, Type.TEXT, chars);
        }

        static Column count(String header) {
            return new Column(header, Type.COUNT, 14);
        }

        static Column percent(String header) {
            return new Column(header, Type.PERCENT, 14);
        }

        static Column decimal(String header) {
            return new Column(header, Type.DECIMAL, 14);
        }
    }

    private final XSSFWorkbook workbook = new XSSFWorkbook();
    private final CellStyle headerStyle;
    private final CellStyle percentStyle;
    private final CellStyle countStyle;
    private final CellStyle decimalStyle;

    XlsxBook() {
        Font bold = workbook.createFont();
        bold.setBold(true);
        headerStyle = workbook.createCellStyle();
        headerStyle.setFont(bold);

        percentStyle = workbook.createCellStyle();
        percentStyle.setDataFormat(workbook.createDataFormat().getFormat("0.0%"));

        countStyle = workbook.createCellStyle();
        countStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));

        decimalStyle = workbook.createCellStyle();
        decimalStyle.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
    }

    Tab tab(String name, Column... columns) {
        Sheet sheet = workbook.createSheet(name);
        Row header = sheet.createRow(0);
        for (int i = 0; i < columns.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(columns[i].header());
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, columns[i].chars() * 256);
        }
        // The header stays put while a long month list scrolls.
        sheet.createFreezePane(1, 1);
        return new Tab(sheet, List.of(columns));
    }

    byte[] bytes() throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            workbook.close();
            return out.toByteArray();
        }
    }

    /** One sheet's data block; rows are appended in order. */
    final class Tab {
        private final Sheet sheet;
        private final List<Column> columns;
        private int nextRow = 1;

        private Tab(Sheet sheet, List<Column> columns) {
            this.sheet = sheet;
            this.columns = columns;
        }

        /** One row, in column order. Values may be null, which leaves a cell empty. */
        void row(Object... values) {
            Row row = sheet.createRow(nextRow++);
            for (int i = 0; i < values.length && i < columns.size(); i++) {
                Object value = values[i];
                if (value == null) {
                    continue;
                }
                Cell cell = row.createCell(i);
                switch (columns.get(i).type()) {
                    case TEXT -> cell.setCellValue(String.valueOf(value));
                    case COUNT -> {
                        cell.setCellValue(((Number) value).doubleValue());
                        cell.setCellStyle(countStyle);
                    }
                    case PERCENT -> {
                        // The API speaks percentages; a percent-formatted cell wants the fraction.
                        cell.setCellValue(((Number) value).doubleValue() / 100.0);
                        cell.setCellStyle(percentStyle);
                    }
                    case DECIMAL -> {
                        cell.setCellValue(((Number) value).doubleValue());
                        cell.setCellStyle(decimalStyle);
                    }
                }
            }
        }

        /** A blank spacer row, for a sheet that lists several small blocks. */
        void blank() {
            nextRow++;
        }
    }
}
