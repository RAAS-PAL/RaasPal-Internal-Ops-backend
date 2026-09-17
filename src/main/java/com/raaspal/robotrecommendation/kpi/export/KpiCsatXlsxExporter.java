package com.raaspal.robotrecommendation.kpi.export;

import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse.Bucket;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse.MonthCsat;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse.SourceFile;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse.Totals;
import com.raaspal.robotrecommendation.kpi.export.XlsxBook.ChartSpec;
import com.raaspal.robotrecommendation.kpi.export.XlsxBook.Column;
import com.raaspal.robotrecommendation.kpi.export.XlsxBook.Stacking;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * CSAT as a workbook, carrying the CSAT page's own charts.
 *
 * <p>The console's charts are HTML and reach a slide only as a picture, which is
 * no use to anyone who then has to correct a figure or recolour a series. So the
 * export hands over the numbers <em>and</em> draws the page's panels as real
 * Excel charts over them: the Top Box chart with its average rule, then the four
 * surveys one at a time, same colours, same order. Each is a chart object, so it
 * pastes into PowerPoint editable — click it and change the data or the fill.
 *
 * <p>The chart sheets hold nothing but their data block, because a title row or a
 * merged banner above it is what makes Excel's own Insert Chart pick the wrong
 * range. Provenance goes on the About sheet instead.
 */
@Component
public class KpiCsatXlsxExporter {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    /** The console's colours, so a survey looks the same in the deck as on screen. */
    private static final String OVERALL_BAR = "#E8A33D";

    /** The four surveys and their pool, in the deck's order. */
    private record Survey(String label, String color, Function<MonthCsat, Bucket> inMonth,
                          Function<Totals, Bucket> inTotals) {
    }

    private static final List<Survey> SURVEYS = List.of(
            new Survey("Installation", "#E0912F", MonthCsat::installation, Totals::installation),
            new Survey("PM", "#7C3AED", MonthCsat::pm, Totals::pm),
            new Survey("CM Delivery", "#6BA6F7", MonthCsat::delivery, Totals::delivery),
            new Survey("CM Cleaning", "#2563EB", MonthCsat::cleaning, Totals::cleaning));

    /** Overall first, then the four — the order of the page's table and side stats. */
    private static final Survey OVERALL =
            new Survey("Overall", OVERALL_BAR, MonthCsat::overall, Totals::overall);

    private static List<Survey> all() {
        return List.of(OVERALL, SURVEYS.get(0), SURVEYS.get(1), SURVEYS.get(2), SURVEYS.get(3));
    }

    public byte[] export(KpiCsatResponse csat) throws IOException {
        XlsxBook book = new XlsxBook();

        topBox(book, csat);
        perSurvey(book, csat, "Responses", Column::count, b -> b.surveyed() ? b.responses() : null);
        perSurvey(book, csat, "Response Rate", Column::percent, Bucket::responseRate);
        byMonthAndSurvey(book, csat);
        periodTotals(book, csat);
        about(book, csat);

        return book.bytes();
    }

    /**
     * The page, as one sheet: Top Box by month for every survey, then its five
     * charts drawn over the block — the overall panel first, at the width the
     * page gives it, and the four surveys in a row beneath as the page has them.
     */
    private static void topBox(XlsxBook book, KpiCsatResponse csat) {
        // One column per survey, then one average per survey: each chart's rule
        // is its own survey's period total, as the page's cards head themselves.
        List<Survey> surveys = all();
        int n = surveys.size();
        Column[] columns = new Column[1 + 2 * n];
        columns[0] = Column.text("Month", 14);
        for (int i = 0; i < n; i++) {
            columns[1 + i] = Column.percent(surveys.get(i).label());
            columns[1 + n + i] = Column.percent("Avg " + surveys.get(i).label());
        }

        XlsxBook.Tab tab = book.tab("Top Box", columns);
        for (MonthCsat month : csat.months()) {
            Object[] row = new Object[columns.length];
            row[0] = monthLabel(month.month());
            for (int i = 0; i < n; i++) {
                row[1 + i] = surveys.get(i).inMonth().apply(month).topBoxRate();
                row[1 + n + i] = surveys.get(i).inTotals().apply(csat.totals()).topBoxRate();
            }
            tab.row(row);
        }

        // Panel 6 at the page's width, then the four surveys in a row beneath.
        for (int i = 0; i < n; i++) {
            Survey survey = surveys.get(i);
            boolean overall = i == 0;
            Integer rule = survey.inTotals().apply(csat.totals()).topBoxRate() == null ? null : 1 + n + i;
            tab.chart(new ChartSpec(overall ? "CSAT Top Box" : survey.label(), Stacking.CLUSTERED,
                    List.of(1 + i), List.of(survey.color()), rule, true, true,
                    overall ? 8 : 27, overall ? 0 : (i - 1) * 5, overall ? 10 : 5, overall ? 18 : 16));
        }
    }

    /** Months down, surveys across — the shape a column chart wants. */
    private static void perSurvey(
            XlsxBook book,
            KpiCsatResponse csat,
            String name,
            Function<String, Column> column,
            Function<Bucket, Number> value
    ) {
        List<Survey> surveys = all();
        Column[] columns = new Column[surveys.size() + 1];
        columns[0] = Column.text("Month", 14);
        for (int i = 0; i < surveys.size(); i++) {
            columns[i + 1] = column.apply(surveys.get(i).label());
        }
        XlsxBook.Tab tab = book.tab(name, columns);
        for (MonthCsat month : csat.months()) {
            Object[] row = new Object[columns.length];
            row[0] = monthLabel(month.month());
            for (int i = 0; i < surveys.size(); i++) {
                row[i + 1] = value.apply(surveys.get(i).inMonth().apply(month));
            }
            tab.row(row);
        }
    }

    /** Every figure with the counts behind it, one row per month per survey. */
    private static void byMonthAndSurvey(XlsxBook book, KpiCsatResponse csat) {
        XlsxBook.Tab tab = book.tab("By Month and Survey",
                Column.text("Month", 14),
                Column.text("Survey", 16),
                Column.percent("Top Box"),
                Column.text("Read from", 12),
                Column.count("Ratings of 5"),
                Column.count("Ratings given"),
                Column.count("Responses"),
                Column.count("Contacted"),
                Column.count("Did not respond"),
                Column.percent("Response rate"));
        for (MonthCsat month : csat.months()) {
            for (Survey survey : all()) {
                Bucket b = survey.inMonth().apply(month);
                tab.row(monthLabel(month.month()), survey.label(), b.topBoxRate(), readFrom(b),
                        counted(b, b.fives()), counted(b, b.ratings()), counted(b, b.responses()),
                        counted(b, b.customers()), counted(b, b.notEvaluated()), b.responseRate());
            }
        }
    }

    /** The five figures the deck prints, with what each was built from. */
    private static void periodTotals(XlsxBook book, KpiCsatResponse csat) {
        XlsxBook.Tab tab = book.tab("Period Totals",
                Column.text("Survey", 16),
                Column.percent("Top Box"),
                Column.text("Read from", 12),
                Column.count("Ratings of 5"),
                Column.count("Ratings given"),
                Column.count("Responses"),
                Column.count("Contacted"),
                Column.count("Did not respond"),
                Column.percent("Response rate"));
        for (Survey survey : all()) {
            Bucket b = survey.inTotals().apply(csat.totals());
            tab.row(survey.label(), b.topBoxRate(), readFrom(b),
                    counted(b, b.fives()), counted(b, b.ratings()), counted(b, b.responses()),
                    counted(b, b.customers()), counted(b, b.notEvaluated()), b.responseRate());
        }
    }

    /** Where the numbers came from and what they mean — off the chart sheets. */
    private static void about(XlsxBook book, KpiCsatResponse csat) {
        XlsxBook.Tab tab = book.tab("About", Column.text("Item", 26), Column.text("Detail", 120));
        tab.row("Report", "RE Team KPI — CSAT (Top Box)");
        tab.row("Period", monthLabel(csat.from()) + " – " + monthLabel(csat.to()));
        tab.row("Figures run to", csat.asOf() == null ? "no workbook has responses" : monthLabel(csat.asOf()));
        tab.row("Live?", "No. The figures change when the RE team replaces the survey workbooks, roughly monthly.");
        if (csat.provisional()) {
            tab.row("Status", "Provisional — the definitions await RE-team sign-off.");
        }
        tab.row("Charts", "On the Top Box sheet, drawn as the console's CSAT page draws them: the overall "
                + "panel, then each survey on its own. They read the cells above, so correcting a figure "
                + "redraws the chart; copy one into a slide and it stays editable there. The 'Avg …' "
                + "columns repeat one figure on every row because that is what a chart needs to draw the rule "
                + "each panel carries.");
        tab.blank();

        tab.row("Source workbooks", csat.sourceFiles().isEmpty() ? "none could be read" : null);
        for (SourceFile file : csat.sourceFiles()) {
            String covers = file.firstMonth() == null || file.lastMonth() == null
                    ? "no month sheets"
                    : monthLabel(file.firstMonth()) + " – " + monthLabel(file.lastMonth());
            tab.row(file.stream() == null ? "survey not recognised" : file.stream(),
                    file.name() + "  (" + covers + ", updated " + file.lastModified() + ")");
        }
        tab.blank();

        for (Map.Entry<String, String> definition : csat.definitions().entrySet()) {
            tab.row(definition.getKey(), definition.getValue());
        }

        if (!csat.warnings().isEmpty()) {
            tab.blank();
            tab.row("Read with warnings", "the figures come from what could be read");
            for (String warning : csat.warnings()) {
                tab.row(null, warning);
            }
        }
    }

    /** "sheet cell" or "pooled" — the distinction the whole feature turns on. */
    private static String readFrom(Bucket bucket) {
        if (!bucket.surveyed()) {
            return "not surveyed";
        }
        return bucket.topBoxFromSheet() ? "sheet cell" : "pooled";
    }

    /** A count only means something where a survey ran; elsewhere the cell stays empty. */
    private static Integer counted(Bucket bucket, int value) {
        return bucket.surveyed() ? value : null;
    }

    private static String monthLabel(String yearMonth) {
        return YearMonth.parse(yearMonth).format(MONTH);
    }
}
