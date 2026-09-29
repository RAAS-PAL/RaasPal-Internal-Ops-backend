package com.raaspal.robotrecommendation.casereport.adapters.googlesheet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Locale;

/**
 * Turning an unformatted Sheets value into text or a date.
 *
 * <p>The API is asked for unformatted values, so a cell arrives as a String, a Number or a
 * Boolean. Numbers are why this exists: an id typed as {@code 1} comes back as a number,
 * and printed naively it becomes {@code 1.0} - which would never match the same case on a
 * later sync if the cell were ever retyped as text.
 */
public final class SheetCells {

    /**
     * Day zero of a spreadsheet date serial. 30 December 1899, not the 31st, because the
     * format inherits Lotus 1-2-3's phantom 29 February 1900; Google Sheets matches Excel
     * so that files move between them unchanged.
     */
    private static final LocalDate SERIAL_EPOCH = LocalDate.of(1899, 12, 30);

    /** 31 December 9999, the largest serial either program will display as a date. */
    private static final double MAX_SERIAL = 2_958_465;

    /**
     * For a date typed as text rather than entered as a date - the cell then holds a
     * string and no serial exists. The column shows {@code 26-Jul-23}; the rest are the
     * other shapes a person types.
     */
    private static final List<DateTimeFormatter> TEXT_DATES = List.of(
            formatter("yyyy-MM-dd"),
            formatter("d-MMM-yy"),
            formatter("d-MMM-yyyy"),
            formatter("d MMM yy"),
            formatter("d MMM yyyy"),
            formatter("d/M/yyyy"),
            formatter("d/M/yy"));

    private SheetCells() {
    }

    /** Trimmed text, or null for an empty cell. Whole numbers print without a decimal. */
    public static String text(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        String text = value.toString().replace(' ', ' ').trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * A date from a serial day count or, failing that, from text. Null when the cell is
     * empty or holds something that is not a date - never a guess.
     */
    public static LocalDate date(Object value) {
        if (value == null) return null;

        if (value instanceof Number number) {
            double serial = number.doubleValue();
            if (serial < 1 || serial > MAX_SERIAL) return null;
            // The fraction is the time of day.
            return SERIAL_EPOCH.plusDays((long) Math.floor(serial));
        }

        String text = text(value);
        if (text == null) return null;

        for (DateTimeFormatter format : TEXT_DATES) {
            try {
                LocalDate date = LocalDate.parse(text, format);
                // A four-digit Buddhist-era year (2566) is 543 ahead of the Gregorian one.
                return date.getYear() > 2400 ? date.minusYears(543) : date;
            } catch (Exception ignored) {
                // Try the next shape.
            }
        }
        return null;
    }

    private static DateTimeFormatter formatter(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }
}
