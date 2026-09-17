package com.raaspal.robotrecommendation.kpi.export;

import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.CmCounts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.InstallCounts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.MonthMetrics;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.Segment;
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

/**
 * The RE report's KPIs as a workbook, one sheet per panel of the deck — data
 * block first, then that panel's own chart drawn over it.
 *
 * <p>Each chart is the console's: same type (a rate as one series, CM volume and
 * SLA stacked, first time fix compared line against line), same colours, and the
 * average rule the panel carries. They are real Excel charts reading the cells
 * above them, so a figure can be corrected or a series recoloured after the
 * chart is pasted into a slide — which a picture of the page cannot do.
 *
 * <p>Every sheet therefore holds its own chart's data, even where that repeats a
 * column from another sheet: a chart whose series live two sheets away is a
 * chart nobody can edit with confidence.
 *
 * <p>PM Complete is absent, as it is from the page: nothing in the backend
 * sources it. The About sheet says so rather than leaving a reader to wonder
 * which of the deck's six panels is missing and why.
 */
@Component
public class KpiCaseXlsxExporter {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    /** The console's series colours. */
    private static final String CLEANING = "#2563EB";
    private static final String DELIVERY = "#6BA6F7";
    private static final String WITHIN = "#34A853";
    private static final String OVER = "#E8A33D";

    public byte[] export(KpiCaseMetricsResponse metrics) throws IOException {
        XlsxBook book = new XlsxBook();

        firstTimeInstall(book, metrics);
        cmCases(book, metrics);
        firstTimeFix(book, metrics);
        sla(book, metrics);
        byServiceLine(book, metrics);
        periodTotals(book, metrics);
        about(book, metrics);

        return book.bytes();
    }

    /** Panel 1: one bar per month, the deck's overall rate, with its average. */
    private static void firstTimeInstall(XlsxBook book, KpiCaseMetricsResponse metrics) {
        XlsxBook.Tab tab = book.tab("1st Time Install",
                Column.text("Month", 14),
                Column.percent("1st Time Install"),
                Column.percent("Avg"),
                Column.count("Installs"),
                Column.count("First time"),
                Column.count("A CM followed"));
        Double average = metrics.totals().all().installation().firstTimeRate();
        for (MonthMetrics month : metrics.months()) {
            InstallCounts i = month.all().installation();
            tab.row(monthLabel(month.month()), i.firstTimeRate(), average, i.total(), i.firstTime(),
                    i.followedByCm());
        }
        tab.chart(new ChartSpec("1st Time Install", Stacking.CLUSTERED, List.of(1), List.of(CLEANING),
                average == null ? null : 2, true, true, 9, 0, 10, 18));
    }

    /** Panel 3: the two service lines stacked, as the deck stacks them. */
    private static void cmCases(XlsxBook book, KpiCaseMetricsResponse metrics) {
        XlsxBook.Tab tab = book.tab("CM Cases",
                Column.text("Month", 14),
                Column.count("Cleaning"),
                Column.count("Delivery"),
                Column.count("Avg"),
                Column.count("Total"));
        int months = Math.max(metrics.months().size(), 1);
        double average = (double) metrics.totals().all().cm().total() / months;
        for (MonthMetrics month : metrics.months()) {
            tab.row(monthLabel(month.month()), month.cleaning().cm().total(),
                    month.delivery().cm().total(), average, month.all().cm().total());
        }
        tab.chart(new ChartSpec("Total CM Cases", Stacking.STACKED, List.of(1, 2),
                List.of(CLEANING, DELIVERY), 3, false, true, 9, 0, 10, 18));
    }

    /** Panel 4: cleaning against delivery, side by side, as the panel compares them. */
    private static void firstTimeFix(XlsxBook book, KpiCaseMetricsResponse metrics) {
        XlsxBook.Tab tab = book.tab("First Time Fix",
                Column.text("Month", 14),
                Column.percent("Cleaning"),
                Column.percent("Delivery"),
                Column.percent("Avg"),
                Column.percent("Fleet"),
                Column.count("CM cases"),
                Column.count("Fixed first time"),
                Column.count("A repeat followed"));
        Double average = metrics.totals().all().cm().firstTimeFixRate();
        for (MonthMetrics month : metrics.months()) {
            CmCounts c = month.all().cm();
            tab.row(monthLabel(month.month()), month.cleaning().cm().firstTimeFixRate(),
                    month.delivery().cm().firstTimeFixRate(), average, c.firstTimeFixRate(),
                    c.total(), c.firstTimeFix(), c.repeat());
        }
        tab.chart(new ChartSpec("First Time Fix", Stacking.CLUSTERED, List.of(1, 2),
                List.of(CLEANING, DELIVERY), average == null ? null : 3, true, true, 9, 0, 10, 18));
    }

    /**
     * Panel 5: within and over as shares of one column, so every month is full
     * height and the split is what moves. Cases with no RE Action date are in
     * neither share — they are a count beside the chart, not a third band.
     */
    private static void sla(XlsxBook book, KpiCaseMetricsResponse metrics) {
        XlsxBook.Tab tab = book.tab("SLA",
                Column.text("Month", 14),
                Column.percent("Within SLA"),
                Column.percent("Over SLA"),
                Column.percent("Avg"),
                Column.count("Within"),
                Column.count("Over"),
                Column.count("No RE Action date"));
        Double average = metrics.totals().all().cm().slaWithinRate();
        for (MonthMetrics month : metrics.months()) {
            CmCounts c = month.all().cm();
            Double within = c.slaWithinRate();
            Double over = within == null ? null : Math.round((100 - within) * 10) / 10.0;
            tab.row(monthLabel(month.month()), within, over, average, c.slaWithin(), c.slaOver(),
                    c.slaUnknown());
        }
        tab.chart(new ChartSpec("SLA — within / over", Stacking.STACKED, List.of(1, 2),
                List.of(WITHIN, OVER), average == null ? null : 3, true, true, 9, 0, 10, 18));
    }

    /** The deck compares the two lines on every rate; this is that comparison, as data. */
    private static void byServiceLine(XlsxBook book, KpiCaseMetricsResponse metrics) {
        XlsxBook.Tab tab = book.tab("By Service Line",
                Column.text("Month", 14),
                Column.percent("Install — cleaning"),
                Column.percent("Install — delivery"),
                Column.percent("FTF — cleaning"),
                Column.percent("FTF — delivery"),
                Column.percent("SLA — cleaning"),
                Column.percent("SLA — delivery"));
        for (MonthMetrics month : metrics.months()) {
            Segment cleaning = month.cleaning();
            Segment delivery = month.delivery();
            tab.row(monthLabel(month.month()),
                    cleaning.installation().firstTimeRate(), delivery.installation().firstTimeRate(),
                    cleaning.cm().firstTimeFixRate(), delivery.cm().firstTimeFixRate(),
                    cleaning.cm().slaWithinRate(), delivery.cm().slaWithinRate());
        }
    }

    /**
     * The period's headline figures. Pre-formatted text rather than numbers,
     * because one column holding both a percentage and a case count could not
     * carry a format that suits either.
     */
    private static void periodTotals(XlsxBook book, KpiCaseMetricsResponse metrics) {
        XlsxBook.Tab tab = book.tab("Period Totals",
                Column.text("KPI", 22),
                Column.text("Figure", 14),
                Column.count("Numerator"),
                Column.count("Denominator"),
                Column.text("Of", 26));
        InstallCounts install = metrics.totals().all().installation();
        CmCounts cm = metrics.totals().all().cm();
        tab.row("1st Time Install", rate(install.firstTimeRate()), install.firstTime(), install.total(), "installs");
        tab.row("Total CM Cases", count(cm.total()), null, null, "cleaning + delivery");
        tab.row("First Time Fix", rate(cm.firstTimeFixRate()), cm.firstTimeFix(), cm.total(), "CM cases");
        tab.row("SLA Within", rate(cm.slaWithinRate()), cm.slaWithin(), cm.slaWithin() + cm.slaOver(),
                "CM cases with an RE Action date");
    }

    /** The rules, the windows and the gaps — off the chart sheets. */
    private static void about(XlsxBook book, KpiCaseMetricsResponse metrics) {
        XlsxBook.Tab tab = book.tab("About", Column.text("Item", 26), Column.text("Detail", 120));
        tab.row("Report", "RE Team KPI — CM cases, installs, first time fix, SLA");
        tab.row("Period", monthLabel(metrics.from()) + " – " + monthLabel(metrics.to()));
        tab.row("Source", "monday.com boards, mirrored into the ops database");
        tab.row("Last synced", metrics.lastSyncedAt() == null ? "never" : String.valueOf(metrics.lastSyncedAt()));
        if (metrics.provisional()) {
            tab.row("Status", "Provisional — the definitions await RE-team sign-off.");
        }
        tab.row("Charts", "One per sheet, drawn as the console's report page draws that panel. They read the "
                + "cells above, so correcting a figure redraws the chart; copy one into a slide and it stays "
                + "editable there.");
        tab.row("PM Complete", "Not in this workbook. Nothing in the backend sources it yet — it needs the PM "
                + "visit board and the visits-due-per-robot rule.");
        tab.row("CSAT", "Not in this workbook. It comes from the survey workbooks and has its own export.");
        tab.blank();

        tab.row("Install follow-up window", metrics.installFollowUpDays() + " days");
        tab.row("Repeat window", metrics.repeatWindowDays() + " days");
        tab.row("SLA threshold", metrics.slaDays() == null
                ? "the CM boards disagree, so no single threshold is reported"
                : metrics.slaDays() + " days");
        tab.row("Tickets read", String.valueOf(metrics.ticketCount()));
        tab.row("Unclassified", metrics.unclassifiedTickets() + " — counted in the totals, in neither service line");
        tab.row("Excluded by category", metrics.excludedByCategory() + " — not a KPI case, never in a denominator");
        tab.blank();

        for (Map.Entry<String, String> definition : metrics.definitions().entrySet()) {
            tab.row(definition.getKey(), definition.getValue());
        }
    }

    private static String rate(Double value) {
        return value == null ? "—" : String.format(Locale.ENGLISH, "%.1f%%", value);
    }

    private static String count(int value) {
        return String.format(Locale.ENGLISH, "%,d", value);
    }

    private static String monthLabel(String yearMonth) {
        return YearMonth.parse(yearMonth).format(MONTH);
    }
}
