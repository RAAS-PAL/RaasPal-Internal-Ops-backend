package com.raaspal.robotrecommendation.kpi.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * CSAT for a month range, from the RE team's survey workbooks. Raw counts
 * throughout with the rates pre-computed, like the CM-case response; the
 * console formats them.
 *
 * <p>Every month carries the four surveys and their pool. Each sheet's Top Box
 * is read as the RE team wrote it; the pool and the range totals, which no
 * sheet holds, are combined the way the deck combines them.
 *
 * @param months      one entry per month in the range, in order; a month no
 *                    workbook covers is present with empty buckets
 * @param totals      the same counters pooled over the whole range
 * @param sourceFiles the workbooks these figures came from, with their timestamps
 * @param asOf        the latest month any workbook has responses for; null when
 *                    none does. CSAT moves when the workbooks are replaced, and
 *                    this is how the console says how fresh it is
 * @param provisional true while the definitions await RE-team sign-off
 * @param warnings    anything found while reading the workbooks that the reader
 *                    should know: a sheet that could not be parsed, a file whose
 *                    survey could not be told, a survey with no workbook
 * @param definitions each formula in words, so a board number traces to its rule
 */
public record KpiCsatResponse(
        String from,
        String to,
        List<MonthCsat> months,
        Totals totals,
        List<SourceFile> sourceFiles,
        String asOf,
        boolean provisional,
        List<String> warnings,
        Map<String, String> definitions
) {

    public record MonthCsat(String month, Bucket overall, Bucket installation, Bucket pm, Bucket cleaning, Bucket delivery) {
    }

    public record Totals(Bucket overall, Bucket installation, Bucket pm, Bucket cleaning, Bucket delivery) {
    }

    /**
     * One survey (or the pool) over one month (or the range).
     *
     * @param surveyed     false when no workbook has a sheet for this: the zeros
     *                     below then mean "not surveyed", not "nobody was happy"
     * @param customers    customers the team tried to reach
     * @param responses    of those, ones who answered
     * @param notEvaluated ones who did not
     * @param topBoxRate      for one survey in one month, the sheet's own Top Box cell
     *                        as a percentage. For a range or the pool — which no sheet
     *                        holds — all ratings of 5 over all ratings given, as the
     *                        deck does it; null when nobody answered
     * @param topBoxFromSheet true when {@code topBoxRate} is one sheet's own cell,
     *                        false when it had to be combined. The console says
     *                        which, since "read from the sheet" and "pooled the way
     *                        the deck pools" are different claims about a figure
     * @param fives           ratings of 5 given, summed over whatever this bucket
     *                        covers — the numerator of a combined Top Box
     * @param ratings         ratings given at all, the matching denominator. Returned
     *                        so the console can show the arithmetic rather than assert
     *                        it: a reader who wonders why six months of 60/100/83/100/
     *                        100/100 come to 79.2% can see the answer counts
     * @param responseRate    {@code responses / customers} as a percentage; null
     *                        when nobody was contacted
     */
    public record Bucket(
            boolean surveyed,
            int customers,
            int responses,
            int notEvaluated,
            Double topBoxRate,
            boolean topBoxFromSheet,
            int fives,
            int ratings,
            Double responseRate
    ) {
    }

    /** @param stream the survey the file turned out to be, by its API key */
    public record SourceFile(String name, String stream, LocalDateTime lastModified, String firstMonth, String lastMonth) {
    }
}
