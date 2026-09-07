package com.raaspal.robotrecommendation.kpi.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The CM-case KPIs for a month range, computed from the synced tickets. Raw
 * counts throughout, with the two rates the deck headlines pre-computed; the
 * console formats and colours them.
 *
 * @param months        one entry per month in the range, in order, zero-filled
 *                      where nothing was opened
 * @param totals        the same counters over the whole range
 * @param ticketCount   tickets the range was computed from
 * @param lastSyncedAt  when the mirror was last refreshed; null when it never was
 * @param provisional   always true for now — the definitions below are this
 *                      module's, not yet signed off against the RE team's sheet
 * @param definitions   how each counter was derived, in words, so a number on a
 *                      board slide can be traced to its rule
 */
public record KpiCaseMetricsResponse(
        String from,
        String to,
        List<MonthMetrics> months,
        Totals totals,
        long ticketCount,
        LocalDateTime lastSyncedAt,
        int repeatWindowDays,
        boolean provisional,
        Map<String, String> definitions
) {

    /** One month, with the same counters for all tickets and for each service line. */
    public record MonthMetrics(String month, Counts all, Counts cleaning, Counts delivery) {
    }

    public record Totals(Counts all, Counts cleaning, Counts delivery) {
    }

    /**
     * @param total            tickets opened in the bucket
     * @param closed           of those, closed (close date set or status finished)
     * @param slaWithin        closed within the board's SLA days
     * @param slaOver          closed after it
     * @param slaUnknown       open, or closed without a close date
     * @param firstTimeFix     not followed by a repeat for the same serial within the window
     * @param repeat           followed by one
     * @param withoutSerial    tickets naming no serial — counted as first-time fix, since a repeat cannot be detected
     * @param slaWithinRate    {@code slaWithin / (slaWithin + slaOver)} as a percentage, null when nothing was measurable
     * @param firstTimeFixRate {@code firstTimeFix / total} as a percentage, null when the bucket is empty
     */
    public record Counts(
            int total,
            int closed,
            int slaWithin,
            int slaOver,
            int slaUnknown,
            int firstTimeFix,
            int repeat,
            int withoutSerial,
            Double slaWithinRate,
            Double firstTimeFixRate
    ) {
    }
}
