package com.raaspal.robotrecommendation.kpi.export;

import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.CmCounts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.InstallCounts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.MonthMetrics;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.Segment;
import com.raaspal.robotrecommendation.kpi.export.XlsxBook.Column;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * The RE report's KPIs as a workbook, one sheet per panel of the deck.
 *
 * <p>Each sheet leads with the figure the panel charts and follows it with the
 * counts it came from, so a reader can chart the rate and still see the
 * numerator and denominator beside it. Months run down the rows because that is
 * the axis the deck uses.
 *
 * <p>PM Complete is absent, as it is from the page: nothing in the backend
 * sources it. The About sheet says so rather than leaving a reader to wonder
 * which of the deck's six panels is missing and why.
 */
@Component
public class KpiCaseXlsxExporter {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

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

    /** Panel 1. */
    private static void firstTimeInstall(XlsxBook book, KpiCaseMetricsResponse metrics) {
        XlsxBook.Tab tab = book.tab("1st Time Install",
                Column.text("Month", 14),
                Column.percent("1st time install"),
                Column.count("Installs"),
                Column.count("First time"),
                Column.count("A CM followed"));
        for (MonthMetrics month : metrics.months()) {
            InstallCounts i = month.all().installation();
            tab.row(monthLabel(month.month()), i.firstTimeRate(), i.total(), i.firstTime(), i.followedByCm());
        }
    }

    /** Panel 3 — the deck stacks the two lines, so they are separate columns. */
    private static void cmCases(XlsxBook book, KpiCaseMetricsResponse metrics) {
        XlsxBook.Tab tab = book.tab("CM Cases",
                Column.text("Month", 14),
                Column.count("Cleaning"),
                Column.count("Delivery"),
                Column.count("Total"));
        for (MonthMetrics month : metrics.months()) {
            tab.row(monthLabel(month.month()), month.cleaning().cm().total(),
                    month.delivery().cm().total(), month.all().cm().total());
        }
    }

    /** Panel 4. */
    private static void firstTimeFix(XlsxBook book, KpiCaseMetricsResponse metrics) {
        XlsxBook.Tab tab = book.tab("First Time Fix",
                Column.text("Month", 14),
                Column.percent("First time fix"),
                Column.count("CM cases"),
                Column.count("Fixed first time"),
                Column.count("A repeat followed"));
        for (MonthMetrics month : metrics.months()) {
            CmCounts c = month.all().cm();
            tab.row(monthLabel(month.month()), c.firstTimeFixRate(), c.total(), c.firstTimeFix(), c.repeat());
        }
    }

    /** Panel 5 — within and over stack; unknown is neither and stays out of the rate. */
    private static void sla(XlsxBook book, KpiCaseMetricsResponse metrics) {
        XlsxBook.Tab tab = book.tab("SLA",
                Column.text("Month", 14),
                Column.percent("Within SLA"),
                Column.count("Within"),
                Column.count("Over"),
                Column.count("No RE Action date"));
        for (MonthMetrics month : metrics.months()) {
            CmCounts c = month.all().cm();
            tab.row(monthLabel(month.month()), c.slaWithinRate(), c.slaWithin(), c.slaOver(), c.slaUnknown());
        }
    }

    /** The deck compares the two lines on every rate; this is that comparison. */
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
