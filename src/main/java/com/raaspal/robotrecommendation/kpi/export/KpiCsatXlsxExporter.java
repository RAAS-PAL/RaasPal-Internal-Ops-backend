package com.raaspal.robotrecommendation.kpi.export;

import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse.Bucket;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse.MonthCsat;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse.SourceFile;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse.Totals;
import com.raaspal.robotrecommendation.kpi.export.XlsxBook.Column;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * CSAT as a workbook, so the figures can be charted in a deck.
 *
 * <p>The console's charts are HTML and cannot be pasted into PowerPoint as
 * anything but a picture, and a picture is no use to someone who has to correct
 * a number or recolour a series on the slide. This exports the numbers instead:
 * one sheet per chart the deck draws, months down and surveys across, so
 * Insert Chart produces the panel in two clicks and the result stays editable.
 *
 * <p>The first three sheets are chart fodder and hold nothing else. Everything a
 * reader needs to trust them — which workbook each figure came from, how far the
 * files run, what was combined rather than read — is on the About sheet, because
 * a note above a table is what stops Excel from finding the series names.
 */
@Component
public class KpiCsatXlsxExporter {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    /** The four surveys and their pool, in the deck's order. */
    private record Survey(String label, Function<MonthCsat, Bucket> inMonth, Function<Totals, Bucket> inTotals) {
    }

    private static final List<Survey> SURVEYS = List.of(
            new Survey("Overall", MonthCsat::overall, Totals::overall),
            new Survey("Installation", MonthCsat::installation, Totals::installation),
            new Survey("PM", MonthCsat::pm, Totals::pm),
            new Survey("CM Delivery", MonthCsat::delivery, Totals::delivery),
            new Survey("CM Cleaning", MonthCsat::cleaning, Totals::cleaning));

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

    /** The page's headline chart: Top Box by month, one column per survey. */
    private static void topBox(XlsxBook book, KpiCsatResponse csat) {
        perSurvey(book, csat, "Top Box", Column::percent, Bucket::topBoxRate);
    }

    /** Months down, surveys across — the shape a column chart wants. */
    private static void perSurvey(
            XlsxBook book,
            KpiCsatResponse csat,
            String name,
            Function<String, Column> column,
            Function<Bucket, Number> value
    ) {
        Column[] columns = new Column[SURVEYS.size() + 1];
        columns[0] = Column.text("Month", 14);
        for (int i = 0; i < SURVEYS.size(); i++) {
            columns[i + 1] = column.apply(SURVEYS.get(i).label());
        }
        XlsxBook.Tab tab = book.tab(name, columns);
        for (MonthCsat month : csat.months()) {
            Object[] row = new Object[columns.length];
            row[0] = monthLabel(month.month());
            for (int i = 0; i < SURVEYS.size(); i++) {
                row[i + 1] = value.apply(SURVEYS.get(i).inMonth().apply(month));
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
            for (Survey survey : SURVEYS) {
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
        for (Survey survey : SURVEYS) {
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
