package com.raaspal.robotrecommendation.kpi.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The RE KPIs for a month range. Raw counts throughout, with the three headline
 * rates pre-computed; the console formats and colours them.
 *
 * <p>Every figure appears three times — {@code all}, {@code cleaning},
 * {@code delivery} — because a robot is one type or the other and the deck
 * splits every KPI that way. Installations and CMs are counted separately
 * inside each, so the dashboard's three streams (installation, cleaning,
 * delivery) all come from this one response.
 *
 * @param months        one entry per month in the range, in order, zero-filled
 * @param totals        the same counters over the whole range
 * @param ticketCount   tickets the range was computed from
 * @param unclassifiedTickets  of those, ones counted in {@code all} but in neither
 *                      {@code cleaning} nor {@code delivery}, because nothing said
 *                      which kind of robot they concern. When this is above zero the
 *                      split does not add up to the total, and that is the honest
 *                      reading rather than a rounding fault
 * @param excludedByCategory rows in the range whose category the board config does
 *                      not count (a survey job on the installation board, a parts
 *                      shipment on a CM board). Stated so the denominator never
 *                      shrinks silently
 * @param slaDays       the SLA threshold in days when every CM board agrees on one,
 *                      else null — SLA is measured on CM cases only
 * @param lastSyncedAt  when the mirror was last refreshed; null when it never was
 * @param provisional   true while the definitions await RE-team sign-off
 * @param definitions   each formula in words, so a board number traces to its rule
 */
public record KpiCaseMetricsResponse(
        String from,
        String to,
        List<MonthMetrics> months,
        Totals totals,
        long ticketCount,
        long unclassifiedTickets,
        long excludedByCategory,
        LocalDateTime lastSyncedAt,
        int repeatWindowDays,
        int installFollowUpDays,
        Integer slaDays,
        boolean provisional,
        Map<String, String> definitions
) {

    public record MonthMetrics(String month, Segment all, Segment cleaning, Segment delivery) {
    }

    public record Totals(Segment all, Segment cleaning, Segment delivery) {
    }

    /** One slice of the fleet: its installations and its corrective maintenance. */
    public record Segment(InstallCounts installation, CmCounts cm) {
    }

    /**
     * 1st Time Install.
     *
     * @param total          installations finishing in the bucket
     * @param firstTime      no CM for the same serial within the follow-up window
     * @param followedByCm   a CM did follow — these score zero
     * @param withoutSerial  installations naming no serial, so unmatchable; counted in {@code firstTime}
     * @param firstTimeRate  {@code firstTime / total} as a percentage, null when the bucket is empty
     */
    public record InstallCounts(
            int total,
            int firstTime,
            int followedByCm,
            int withoutSerial,
            Double firstTimeRate
    ) {
    }

    /**
     * CM volume, First Time Fix and SLA.
     *
     * @param total            CM cases reported in the bucket
     * @param firstTimeFix     no later CM for the same serial within the repeat window
     * @param repeat           one followed — these score zero
     * @param withoutSerial    CMs naming no serial, so unmatchable; counted in {@code firstTimeFix}
     * @param slaWithin        checked within the SLA days of being reported
     * @param slaOver          checked later than that
     * @param slaUnknown       no RE Action date recorded, so it cannot be said either way
     * @param firstTimeFixRate {@code firstTimeFix / total} as a percentage, null when empty
     * @param slaWithinRate    {@code slaWithin / (slaWithin + slaOver)} as a percentage, null when nothing measurable
     */
    public record CmCounts(
            int total,
            int firstTimeFix,
            int repeat,
            int withoutSerial,
            int slaWithin,
            int slaOver,
            int slaUnknown,
            Double firstTimeFixRate,
            Double slaWithinRate
    ) {
    }
}
