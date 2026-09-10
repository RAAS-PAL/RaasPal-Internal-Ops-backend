package com.raaspal.robotrecommendation.kpi.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xddf.usermodel.XDDFColor;
import org.apache.poi.xddf.usermodel.XDDFLineProperties;
import org.apache.poi.xddf.usermodel.XDDFShapeProperties;
import org.apache.poi.xddf.usermodel.XDDFSolidFillProperties;
import org.apache.poi.xddf.usermodel.chart.AxisCrossBetween;
import org.apache.poi.xddf.usermodel.chart.AxisCrosses;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.AxisTickMark;
import org.apache.poi.xddf.usermodel.chart.BarDirection;
import org.apache.poi.xddf.usermodel.chart.BarGrouping;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.Grouping;
import org.apache.poi.xddf.usermodel.chart.LegendPosition;
import org.apache.poi.xddf.usermodel.chart.MarkerStyle;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFLineChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChartLegend;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTBarSer;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTDLbl;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTDLbls;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTLineSer;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTNumFmt;
import org.openxmlformats.schemas.drawingml.x2006.chart.STDLblPos;

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
 *
 * <p>Sheets can also carry the chart itself — a real Excel chart object reading
 * the cells beside it, drawn to match the panel on the console. Opening the file
 * shows the picture; clicking the chart still gets you the data.
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

    /**
     * How several series share a column: side by side, or one on top of another.
     *
     * <p>Not called Grouping: POI's chart package has a Grouping of its own, and
     * a nested type of that name silently shadows the import.
     */
    enum Stacking { CLUSTERED, STACKED }

    /**
     * One chart to draw beside the data.
     *
     * @param columns   the data columns to plot, 0-based; the month column is
     *                  always the category axis
     * @param colors    one {@code #RRGGBB} per column, the console's own
     * @param averageColumn a column holding the period average on every row,
     *                      drawn as the dark rule the panels carry across the
     *                      bars; null for a panel that has none
     * @param percent   fixes the axis at 0–100% and formats it as a percentage,
     *                  as the deck's rate charts do
     * @param labels    prints each bar's value above it, as the deck does
     * @param atRow     top-left corner, in cells, so charts sit clear of the table
     */
    record ChartSpec(
            String title,
            Stacking stacking,
            List<Integer> columns,
            List<String> colors,
            Integer averageColumn,
            boolean percent,
            boolean labels,
            int atRow,
            int atColumn,
            int widthColumns,
            int heightRows
    ) {
    }

    /** The average rule's colour — the panels draw it in the text colour. */
    private static final String AVERAGE = "#1F2937";
    /** The page's gridlines: the border token, faint. */
    private static final String GRIDLINE = "#D1D5DB";

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
        XSSFSheet sheet = workbook.createSheet(name);
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
        private final XSSFSheet sheet;
        private final List<Column> columns;
        private int nextRow = 1;

        private Tab(XSSFSheet sheet, List<Column> columns) {
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

        /** The last row holding data, which is as far as a chart should read. */
        int lastDataRow() {
            return nextRow - 1;
        }

        /**
         * Draws a chart over the rows written so far.
         *
         * <p>Series titles are cell references to the header rather than copies
         * of the text, so renaming a column in the sheet renames it in the
         * legend — the file stays a spreadsheet, not a screenshot of one.
         */
        void chart(ChartSpec spec) {
            int lastRow = lastDataRow();
            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0,
                    spec.atColumn(), spec.atRow(),
                    spec.atColumn() + spec.widthColumns(), spec.atRow() + spec.heightRows());
            XSSFChart chart = drawing.createChart(anchor);
            chart.setTitleText(spec.title());
            chart.setTitleOverlay(false);
            // The page's chart carries a header row: the series on the left,
            // the average with a line swatch on the right. The legend is that row
            // — so a panel with a rule gets one even when it has a single series,
            // and the rule itself then only has to print its figure.
            if (spec.columns().size() > 1 || spec.averageColumn() != null) {
                XDDFChartLegend legend = chart.getOrAddLegend();
                legend.setPosition(LegendPosition.BOTTOM);
            }

            XDDFCategoryAxis months = chart.createCategoryAxis(AxisPosition.BOTTOM);
            XDDFValueAxis values = chart.createValueAxis(AxisPosition.LEFT);
            values.setCrosses(AxisCrosses.AUTO_ZERO);
            // Each month owns a band, as it does on the page. POI's default —
            // midCat — plots the first and last month on the plot area's own
            // edges instead, so half of each of those two bars is drawn outside
            // it and Excel clips it: the file opens with January and June looking
            // shaved down their outer side. Between is Excel's own default for a
            // column chart. The rule pays for it, reaching the outermost months'
            // centres rather than the plot's edges.
            values.setCrossBetween(AxisCrossBetween.BETWEEN);
            // Whole percentages and thousand-separated counts, the way the
            // panels print them. The cells keep their own finer format: a bar
            // labelled 87% still sits on a cell reading 86.6%, as on the page.
            String labelFormat = spec.percent() ? "0%" : "#,##0";
            if (spec.percent()) {
                // The deck's rate charts all run 0–100 in quarters, so a good
                // month and a bad one are the same height in every panel. The
                // axis ends a little above 100% though: pinned at exactly 1.0, a
                // 100% bar hits the ceiling and its label lands outside the plot.
                // Ticks are only drawn at multiples of the unit, so nothing says
                // "110%" — the top just has room.
                values.setMinimum(0.0);
                values.setMaximum(1.1);
                values.setMajorUnit(0.25);
                values.setNumberFormat("0%");
            } else {
                values.setNumberFormat(labelFormat);
            }
            // Faint solid gridlines and no tick marks, as on the page.
            values.setMajorTickMark(AxisTickMark.NONE);
            months.setMajorTickMark(AxisTickMark.NONE);
            XDDFLineProperties gridline = new XDDFLineProperties();
            gridline.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(rgb(GRIDLINE))));
            gridline.setWidth(0.75);
            values.getOrAddMajorGridProperties().setLineProperties(gridline);

            XDDFDataSource<String> categories = XDDFDataSourcesFactory.fromStringCellRange(
                    sheet, new CellRangeAddress(1, lastRow, 0, 0));
            XDDFBarChartData bar = (XDDFBarChartData) chart.createData(ChartTypes.BAR, months, values);
            bar.setBarDirection(BarDirection.COL);
            // The page gives a bar 62% of its month's slot; Excel's default gap
            // of 150% of the bar makes them slivers.
            bar.setGapWidth(60);
            // Stated either way: the schema defaults an absent grouping to
            // clustered, but a chart that says what it is survives being edited.
            bar.setBarGrouping(spec.stacking() == Stacking.STACKED
                    ? BarGrouping.STACKED
                    : BarGrouping.CLUSTERED);
            for (int i = 0; i < spec.columns().size(); i++) {
                int column = spec.columns().get(i);
                XDDFNumericalDataSource<Double> data = XDDFDataSourcesFactory.fromNumericCellRange(
                        sheet, new CellRangeAddress(1, lastRow, column, column));
                XDDFBarChartData.Series series = (XDDFBarChartData.Series) bar.addSeries(categories, data);
                series.setTitle(columns.get(column).header(),
                        new CellReference(sheet.getSheetName(), 0, column, true, true));
                XDDFShapeProperties properties = new XDDFShapeProperties();
                properties.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(rgb(spec.colors().get(i)))));
                series.setShapeProperties(properties);
            }
            chart.plot(bar);

            // The panels draw the period average as a rule across the bars. A
            // chart cannot hold a bare horizontal line, so it is a flat series
            // over a column repeating the one value — which is also why that
            // column is in the sheet rather than computed here.
            if (spec.averageColumn() != null) {
                XDDFLineChartData rule = (XDDFLineChartData) chart.createData(ChartTypes.LINE, months, values);
                // A lineChart must declare its grouping; POI writes none, and
                // Excel rejects the whole file rather than the one element.
                rule.setGrouping(Grouping.STANDARD);
                XDDFNumericalDataSource<Double> data = XDDFDataSourcesFactory.fromNumericCellRange(
                        sheet, new CellRangeAddress(1, lastRow, spec.averageColumn(), spec.averageColumn()));
                XDDFLineChartData.Series series = (XDDFLineChartData.Series) rule.addSeries(categories, data);
                series.setTitle(columns.get(spec.averageColumn()).header(),
                        new CellReference(sheet.getSheetName(), 0, spec.averageColumn(), true, true));
                series.setSmooth(false);
                series.setMarkerStyle(MarkerStyle.NONE);
                XDDFShapeProperties properties = new XDDFShapeProperties();
                XDDFLineProperties line = new XDDFLineProperties();
                line.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(rgb(AVERAGE))));
                line.setWidth(2.0);   // points, as the panel's 2px rule
                properties.setLineProperties(line);
                series.setShapeProperties(properties);
                chart.plot(rule);
                labelRule(chart.getCTChart().getPlotArea().getLineChartArray(0).getSerArray(0),
                        ruleLabelPoint(spec, lastRow), spec.percent() ? "0.0%" : "#,##0");
            }

            var barChart = chart.getCTChart().getPlotArea().getBarChartArray(0);
            if (spec.stacking() == Stacking.STACKED) {
                // Without a full overlap Excel draws a stacked chart as separated
                // slivers; POI writes no overlap of its own.
                barChart.addNewOverlap().setVal((byte) 100);
            }
            // POI marks a format it was handed as source-linked, which tells the
            // reader to ignore it and use the cells' own — and then an axis of
            // fractions renders as 0 to 1 rather than 0% to 100%.
            chart.getCTChart().getPlotArea().getValAxArray(0).getNumFmt().setSourceLinked(false);

            if (spec.labels()) {
                for (CTBarSer series : barChart.getSerArray()) {
                    labelValues(series, labelFormat);
                }
            }
        }

        /**
         * Which month carries the rule's figure: the last one whose bar is clear
         * of it, or failing that the one furthest from it.
         *
         * <p>The figure has to sit at the rule's own height, which is where a bar
         * that happens to match the average prints its value too — and two
         * numbers in one place are neither readable. The page lifts the bar's
         * label over the rule; a chart cannot be told to do that, so the rule's
         * label moves along the line instead, to a month where there is room.
         *
         * <p>The heights come back out of the cells just written, so this reads
         * exactly what the chart reads. A month with no bar at all counts as
         * clear: an empty band is the best place a label can land.
         */
        private int ruleLabelPoint(ChartSpec spec, int lastRow) {
            double rule = numeric(1, spec.averageColumn());
            // Percent panels are pinned; a count panel's axis is as tall as its
            // tallest column, near enough for measuring a label against.
            double span = spec.percent() ? 1.1 : 0.0;
            double[] tops = new double[lastRow];
            for (int row = 1; row <= lastRow; row++) {
                double top = 0;
                for (int column : spec.columns()) {
                    double value = numeric(row, column);
                    top = spec.stacking() == Stacking.STACKED ? top + value : Math.max(top, value);
                }
                tops[row - 1] = top;
                span = Math.max(span, top);
            }
            // A label stands about a tenth of the plot high, counting its air.
            double clear = span * 0.1;
            int furthest = 0;
            for (int i = 0; i < tops.length; i++) {
                if (Math.abs(tops[i] - rule) > Math.abs(tops[furthest] - rule)) {
                    furthest = i;
                }
            }
            for (int i = tops.length - 1; i >= 0; i--) {
                if (Math.abs(tops[i] - rule) >= clear) {
                    return i;
                }
            }
            return furthest;
        }

        /** A written cell's number, or 0 for a month that has none. */
        private double numeric(int row, int column) {
            Row r = sheet.getRow(row);
            Cell cell = r == null ? null : r.getCell(column);
            return cell == null || cell.getCellType() != CellType.NUMERIC ? 0 : cell.getNumericCellValue();
        }
    }

    /**
     * Prints the value above each bar, formatted rather than raw.
     *
     * <p>Written against the schema types because POI has no API for it, so the
     * children go on in the order the schema declares — numFmt first, then the
     * flags. Out of order and Excel calls the file corrupt rather than pointing
     * at the problem. Without the numFmt at all, a percentage cell labels its
     * bar "0.682".
     */
    private static void labelValues(CTBarSer series, String format) {
        CTDLbls labels = series.isSetDLbls() ? series.getDLbls() : series.addNewDLbls();
        CTNumFmt numberFormat = labels.addNewNumFmt();
        numberFormat.setFormatCode(format);
        numberFormat.setSourceLinked(false);
        labels.addNewShowLegendKey().setVal(false);
        labels.addNewShowVal().setVal(true);
        labels.addNewShowCatName().setVal(false);
        labels.addNewShowSerName().setVal(false);
        labels.addNewShowPercent().setVal(false);
        labels.addNewShowBubbleSize().setVal(false);
    }

    /**
     * Prints the rule's figure once, above the line, at one month.
     *
     * <p>The figure alone: the legend names the line, and a label carrying
     * "Avg CM Delivery 89.7%" as well wraps onto three lines in a panel five
     * columns wide and covers the bars either side of it.
     *
     * <p>A single point's label is a {@code dLbl} inside the series' {@code dLbls},
     * and both carry the same run of show-flags in schema order: the point's
     * turns the value on; the group's, which govern every other point, turn
     * everything off.
     */
    private static void labelRule(CTLineSer series, int pointIndex, String format) {
        CTDLbls labels = series.isSetDLbls() ? series.getDLbls() : series.addNewDLbls();
        CTDLbl label = labels.addNewDLbl();
        label.addNewIdx().setVal(pointIndex);
        CTNumFmt numberFormat = label.addNewNumFmt();
        numberFormat.setFormatCode(format);
        numberFormat.setSourceLinked(false);
        label.addNewDLblPos().setVal(STDLblPos.T);
        label.addNewShowLegendKey().setVal(false);
        label.addNewShowVal().setVal(true);
        label.addNewShowCatName().setVal(false);
        label.addNewShowSerName().setVal(false);
        label.addNewShowPercent().setVal(false);
        label.addNewShowBubbleSize().setVal(false);
        labels.addNewShowLegendKey().setVal(false);
        labels.addNewShowVal().setVal(false);
        labels.addNewShowCatName().setVal(false);
        labels.addNewShowSerName().setVal(false);
        labels.addNewShowPercent().setVal(false);
        labels.addNewShowBubbleSize().setVal(false);
    }

    /** "#E8A33D" as the three bytes a fill wants. */
    private static byte[] rgb(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return new byte[]{
                (byte) Integer.parseInt(h.substring(0, 2), 16),
                (byte) Integer.parseInt(h.substring(2, 4), 16),
                (byte) Integer.parseInt(h.substring(4, 6), 16)};
    }
}
