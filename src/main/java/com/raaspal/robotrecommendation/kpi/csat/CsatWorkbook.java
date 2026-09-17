package com.raaspal.robotrecommendation.kpi.csat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * One survey workbook, reduced to the three figures the KPI needs from each
 * month sheet. Nothing per respondent is kept: the workbook's per-case sheets
 * name customers and branches, and the dashboard has no use for any of that.
 *
 * @param stream   which survey this is; null when neither the sheet title nor
 *                 the file name said, in which case the service reports it and
 *                 leaves it out rather than guess
 * @param months   one entry per month sheet that parsed
 * @param warnings anything found while reading that the reader should know —
 *                 a sheet that could not be parsed, a file whose survey could
 *                 not be told
 */
public record CsatWorkbook(
        String fileName,
        Instant lastModified,
        CsatStream stream,
        Map<YearMonth, MonthAggregate> months,
        List<String> warnings
) {

    /**
     * A month sheet's figures, read as they are.
     *
     * @param customers    customers the team tried to survey that month ("# ลูกค้า")
     * @param responses    of those, ones who answered ("ประเมินผล")
     * @param notEvaluated ones who did not, whatever the reason ("ไม่ได้ประเมินผล")
     * @param topBox       the sheet's own "Top Box" cell — {@code =AVERAGE(Q10:Q14)},
     *                     the RE team's formula — as a fraction. Shown for this
     *                     month exactly as it is; never recomputed
     * @param fives        ratings of 5 over the five questions (the I column)
     * @param ratings      ratings given over the five questions (the N column).
     *                     These two are read only so that months and surveys can
     *                     be combined the way the deck combines them: all fives
     *                     over all ratings. They never override {@code topBox}
     */
    public record MonthAggregate(
            YearMonth month,
            int customers,
            int responses,
            int notEvaluated,
            double topBox,
            int fives,
            int ratings
    ) {
    }
}
