package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.util.Locale;

/**
 * The window one performance report covers: a calendar month or an ISO week
 * (Monday–Sunday).
 *
 * <p>The two are loaded differently on purpose. A month is served straight off
 * the stored {@code report_month} column — indexed, and exactly what every
 * existing report already does. A week cannot be: it routinely straddles two
 * months, so it has to be a {@code start_time} range instead.
 *
 * <p>Weeks are ISO weeks because that is what the browser's native
 * {@code <input type="week">} emits, so the picker and the API speak the same
 * string with nothing to translate between them.
 *
 * @param type       month or week
 * @param key        the request value as given — "2026-08" or "2026-W35"
 * @param startDate  first day covered, or null for an unparseable month
 * @param endDate    last day covered, or null for an unparseable month
 * @param label      the customer-facing period title on the report
 */
public record ReportPeriod(Type type, String key, LocalDate startDate, LocalDate endDate, String label) {

    public enum Type {
        MONTH, WEEK
    }

    /** En dash with spaces — the range separator in the report title. */
    private static final String RANGE_SEPARATOR = " \u2013 ";

    private static final DateTimeFormatter DAY_MONTH_YEAR =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_MONTH =
            DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);

    /**
     * Parses "YYYY-Www". Strict, so an out-of-range week (e.g. 2025-W53, a year
     * with only 52) is rejected rather than silently resolved to a neighbouring
     * week.
     */
    private static final DateTimeFormatter ISO_WEEK = new DateTimeFormatterBuilder()
            .appendValue(IsoFields.WEEK_BASED_YEAR, 4)
            .appendLiteral("-W")
            .appendValue(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 2)
            .parseDefaulting(ChronoField.DAY_OF_WEEK, 1) // Monday
            .toFormatter(Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT);

    /**
     * A calendar month, "YYYY-MM" — e.g. "August 2026".
     *
     * <p>Deliberately tolerant of an unparseable value, preserving the original
     * preview behaviour: the month is matched against stored {@code report_month}
     * values, so a bad one simply matches nothing and renders an empty report
     * labelled with whatever was asked for.
     */
    public static ReportPeriod ofMonth(String month) {
        try {
            YearMonth ym = YearMonth.parse(month);
            String label = Month.of(ym.getMonthValue()).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                    + " " + ym.getYear();
            return new ReportPeriod(Type.MONTH, month, ym.atDay(1), ym.atEndOfMonth(), label);
        } catch (DateTimeException e) {
            return new ReportPeriod(Type.MONTH, month, null, null, month);
        }
    }

    /**
     * An ISO week, "YYYY-Www" — e.g. "17 – 23 August 2026".
     *
     * <p>Rejects a malformed week outright, unlike {@link #ofMonth}. A week is
     * resolved to a date range rather than matched against a stored column, so a
     * typo would otherwise produce a confidently empty report for a window that
     * was never asked for.
     */
    public static ReportPeriod ofWeek(String week) {
        LocalDate monday;
        try {
            monday = LocalDate.parse(week, ISO_WEEK);
        } catch (DateTimeException e) {
            throw new BadRequestException("'week' must be an ISO week such as 2026-W35");
        }
        LocalDate sunday = monday.plusDays(6);
        return new ReportPeriod(Type.WEEK, week, monday, sunday, weekLabel(monday, sunday));
    }

    /** First instant covered, in the business timezone. */
    public Instant startInstant(ZoneId zone) {
        return startDate.atStartOfDay(zone).toInstant();
    }

    /** Last instant covered (inclusive), in the business timezone. */
    public Instant endInstant(ZoneId zone) {
        return endDate.atTime(LocalTime.MAX).atZone(zone).toInstant();
    }

    /**
     * "17 – 23 August 2026", collapsing whatever the two ends share — a week
     * inside one month names the month once, one spanning two months names both,
     * one spanning new year names both years.
     */
    private static String weekLabel(LocalDate start, LocalDate end) {
        if (start.getYear() != end.getYear()) {
            return start.format(DAY_MONTH_YEAR) + RANGE_SEPARATOR + end.format(DAY_MONTH_YEAR);
        }
        if (start.getMonth() != end.getMonth()) {
            return start.format(DAY_MONTH) + RANGE_SEPARATOR + end.format(DAY_MONTH_YEAR);
        }
        return start.getDayOfMonth() + RANGE_SEPARATOR + end.format(DAY_MONTH_YEAR);
    }
}
