package com.raaspal.robotrecommendation.casereport.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether a case is inside its SLA.
 *
 * <p>Computed, never stored. The inputs — a status, an open date, a province — all move,
 * and a stored verdict would be a claim about a customer that quietly went stale.
 *
 * <p><strong>The rules, as the team states them:</strong>
 *
 * <ul>
 *   <li>Delivery robots for <b>MK, Yayoi and Bonus Suki</b>: <b>3 calendar days</b> inside
 *       the six greater-Bangkok provinces, <b>5 days</b> anywhere else. Those three are one
 *       customer — MK Restaurant Group — which is why a single rule covers them all, and
 *       why the branch prefixes M###, Y### and K### sit under it together.</li>
 *   <li>Cleaning reports: <b>3 calendar days everywhere.</b> Province is irrelevant, and
 *       the cleaning board carries no Province column to consult.</li>
 * </ul>
 *
 * <p>Calendar days, not working days.
 *
 * <p>⚠️ The 09 September 2026 workbook disagrees with this rule on two of its sixteen
 * delivery rows: M453 โรบินสัน ฉะเชิงเทรา at 7 days and M057 ศรีราชานคร at 6 days both print
 * "Within SLA", though Chachoengsao and Chonburi are outside the six provinces and so on
 * the 5-day threshold. All fourteen other rows agree. Those two are taken to be slips in a
 * hand-built spreadsheet rather than evidence of a longer threshold, on the team's word —
 * so a generated report will mark them over SLA where today's file does not.
 *
 * <p>Takes <em>already-mapped</em> status values rather than a column payload. The two
 * boards disagree about which column id holds what: {@code status_1} is Issue Level on
 * the cleaning board but Sup Status on delivery, and Sup Status is {@code status7} on
 * cleaning. Resolving that here would put board knowledge in the one class that must be
 * identical for every report, so each generator maps its own board and passes values.
 */
@Service
public class SlaCalculator {

    /**
     * Marks a case whose clock is not ours to run.
     *
     * <p>Matched as a substring because the board's labels are not uniform — real values
     * include "On Hold" and longer Thai phrases containing it — and case-insensitively
     * because they are typed by hand.
     */
    private static final String ON_HOLD_MARKER = "on hold";

    /**
     * Greater Bangkok, normalised for comparison.
     *
     * <p>Config rather than a constant: it is one list shared by every report, and the
     * migration deliberately keeps it out of {@code case_report_definition} so adding a
     * province does not mean editing every definition row.
     */
    private final Set<String> metroProvinces;

    public SlaCalculator(
            @Value("${app.casereport.metro-provinces}") List<String> metroProvinces) {
        this.metroProvinces = metroProvinces.stream()
                .map(SlaCalculator::normalise)
                .filter(p -> !p.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * How many days a case has been open, as the report counts them.
     *
     * <p><strong>Exclusive of the day it opened</strong>: a case opened today reads 0, and
     * {@code Open Date + Days} lands on the report date. The RE team's own files disagree
     * with each other — the 09 September 2026 workbook counted inclusively, the 11
     * September one did not — and exclusive was chosen on 2026-09-11 to match the newer
     * file. A reviewer who wants a different number for one row types it into the row.
     *
     * <p>Public, and the only place this arithmetic lives, because the same number is both
     * printed in the Days column and compared against the threshold. Computing it twice is
     * how a report ends up showing 5 days beside a verdict that judged it as 4.
     */
    public static int daysOpen(LocalDate openDate, LocalDate asOf) {
        return (int) ChronoUnit.DAYS.between(openDate, asOf);
    }

    /**
     * @param status            the board's Status value, already mapped for this board
     * @param supStatus         the board's Sup Status value, already mapped
     * @param province          as recorded on the ticket; null or blank when unknown
     * @param openDate          when the case was opened; null makes the verdict UNKNOWN
     * @param asOf              the business date the report is being produced for
     * @param slaDaysMetro      threshold inside greater Bangkok, from the definition
     * @param slaDaysUpcountry  threshold elsewhere, from the definition
     */
    public SlaStatus evaluate(String status,
                              String supStatus,
                              String province,
                              LocalDate openDate,
                              LocalDate asOf,
                              int slaDaysMetro,
                              int slaDaysUpcountry) {

        // First, because it outranks the arithmetic: a held case is not late even if the
        // days have run past the limit.
        if (isOnHold(status) || isOnHold(supStatus)) {
            return SlaStatus.ON_HOLD;
        }

        if (openDate == null || asOf == null) {
            return SlaStatus.UNKNOWN;
        }

        Integer threshold = thresholdFor(province, slaDaysMetro, slaDaysUpcountry);
        if (threshold == null) {
            return SlaStatus.UNKNOWN;
        }

        int days = daysOpen(openDate, asOf);

        // '>' and not '>=': at exactly the limit a case is still within SLA, so lateness
        // starts the day after. Verified on the 09 Sep 2026 report, where a Bangkok case
        // at Days = 3 prints "Within SLA" against a 3-day threshold; '>=' would turn
        // every case that is precisely on time red.
        return days > threshold ? SlaStatus.BREACHED : SlaStatus.WITHIN;
    }

    /**
     * The day count a case is judged against, or null when it cannot be known.
     *
     * <p>Note the order: when a report's two thresholds are equal — every cleaning
     * report — the province is never consulted at all. That is not an optimisation. It
     * means a cleaning ticket with no province still gets a verdict, where insisting on
     * one would blank out a column that was never province-dependent to begin with.
     */
    private Integer thresholdFor(String province, int slaDaysMetro, int slaDaysUpcountry) {
        if (slaDaysMetro == slaDaysUpcountry) {
            return slaDaysMetro;
        }

        String normalised = normalise(province);
        if (normalised.isEmpty()) {
            // An unknown province must not be guessed at. Defaulting to the longer
            // threshold would be the dangerous direction: it would show a genuinely late
            // metro case as on time, and nobody would look at a green row again.
            return null;
        }

        if (metroProvinces.contains(normalised)) {
            return slaDaysMetro;
        }

        // Present but not on the metro list means upcountry, which is a fact rather than
        // a guess: the metro list is closed at six provinces and the rest of Thailand is
        // the remainder.
        //
        // ⚠️ This holds because Province on the delivery board is a dropdown
        // (color_mm6mwh74), so its values come from 14 defined labels and cannot be
        // misspelled. If a province ever arrives from free text, parsing or AI instead,
        // a near-miss like "Bangkokk" would fall through to the upcountry threshold and
        // read as on time for two extra days.
        return slaDaysUpcountry;
    }

    private static boolean isOnHold(String value) {
        return value != null
                && value.toLowerCase(Locale.ROOT).contains(ON_HOLD_MARKER);
    }

    /**
     * Lower-cased, trimmed, internal whitespace collapsed.
     *
     * <p>So that "Pathum  Thani" and "pathum thani" are the same province. Lower-casing
     * does nothing to the Thai labels but costs nothing either, and the list carries both
     * scripts for each metro province — the board happens to spell all six in Latin
     * today, but that is data-entry habit rather than a rule, and a later
     * "กรุงเทพมหานคร" must not silently become upcountry.
     */
    private static String normalise(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
