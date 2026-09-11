package com.raaspal.robotrecommendation.casereport.service;

import java.time.Month;
import java.time.Year;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic clean-up of a model-written Solution line.
 *
 * <p>The prompt asks for date ranges to stay inside one month, as the RE team's workbook
 * does ({@code 25-31 Aug} then {@code 03-Sep}). The model follows that most of the time and
 * not always — {@code 25-11 Sep} for a state running from 25 August to 11 September was
 * produced twice in a row, once with a hyphen and once with a space. A paraphrase is not
 * deterministic, so the rule that has to hold is enforced here, where it can be tested,
 * rather than restated in the prompt and hoped for.
 */
public final class SolutionLine {

    private static final String MON =
            "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec";

    /**
     * {@code DD-DD Mon phrase}, where the phrase runs to the next date token or the end.
     *
     * <p>Group 1 and 2 are the day numbers, 3 the separator before the month, 4 the month,
     * 5 the phrase. The separator is a space or a hyphen because the model writes both,
     * {@code 25-11 Sep} and {@code 25-11-Sep}, for the same range, and a form this does not
     * recognise goes into the report unsplit. The lookahead that ends the phrase accepts
     * every form of a following date: {@code 03-Sep}, {@code 08-09 Sep} or {@code 08-09-Sep}.
     */
    private static final Pattern RANGE = Pattern.compile(
            "\\b(\\d{1,2})-(\\d{1,2})([- ])(" + MON + ")\\b\\s*(.*?)"
                    + "(?=\\s+\\d{1,2}-(?:\\d{1,2}[- ])?(?:" + MON + ")\\b|$)");

    private SolutionLine() {
    }

    /**
     * Split any range whose first day is after its last — which is only possible if it
     * crossed a month boundary — into one range per month, each carrying the phrase.
     *
     * <p>{@code 25-11 Sep อยู่ระหว่างเบิกอะไหล่} becomes
     * {@code 25-31 Aug อยู่ระหว่างเบิกอะไหล่ 01-11 Sep อยู่ระหว่างเบิกอะไหล่}. The phrase is
     * repeated because that is how the workbook writes a state that spans two ranges: every
     * range has its own phrase, and a bare {@code 25-31 Aug 01-11 Sep} would read as two
     * dates with one description. A side of the split that covers a single day is written
     * as one date, {@code 31-Aug}, never {@code 31-31 Aug}.
     *
     * <p>A hyphenated range inside one month, {@code 25-26-Aug}, is rewritten in the
     * workbook's form, {@code 25-26 Aug}. A range whose first day does not exist in the
     * earlier month, such as {@code 30-05 Mar}, is a wrong date rather than a crossing and
     * is left exactly as written, for the reviewer to catch.
     *
     * @param year the report year, needed only to know how long the earlier month was
     */
    public static String splitCrossMonthRanges(String line, int year) {
        if (line == null || line.isBlank()) return line;

        Matcher m = RANGE.matcher(line);
        StringBuilder out = new StringBuilder();

        while (m.find()) {
            int from = Integer.parseInt(m.group(1));
            int to = Integer.parseInt(m.group(2));
            boolean hyphenated = "-".equals(m.group(3));
            String monthName = m.group(4);
            String phrase = m.group(5).strip();

            if (from <= to) {
                String kept = m.group();
                if (hyphenated) {
                    int separator = m.start(3) - m.start();
                    kept = kept.substring(0, separator) + ' ' + kept.substring(separator + 1);
                }
                m.appendReplacement(out, Matcher.quoteReplacement(kept));
                continue;
            }

            Month month = monthOf(monthName);
            Month previous = month.minus(1);
            // January's previous month is December of the year before.
            int previousYear = month == Month.JANUARY ? year - 1 : year;
            int lastDay = previous.length(Year.isLeap(previousYear));

            if (from > lastDay || to < 1) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }

            String fixed = entry(span(from, lastDay, abbreviate(previous)), phrase)
                    + ' ' + entry(span(1, to, monthName), phrase);

            m.appendReplacement(out, Matcher.quoteReplacement(fixed));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** {@code 25-31 Aug}, or {@code 31-Aug} when the range is a single day. */
    private static String span(int from, int to, String month) {
        return from == to
                ? String.format(Locale.ROOT, "%02d-%s", from, month)
                : String.format(Locale.ROOT, "%02d-%02d %s", from, to, month);
    }

    /** A date and its phrase, with no trailing space when the phrase is empty. */
    private static String entry(String date, String phrase) {
        return phrase.isEmpty() ? date : date + ' ' + phrase;
    }

    /**
     * By the first three letters of the enum name, not by display name. The display name
     * for September is "Sep" in some locales and "Sept" in others, and this must not depend
     * on the JVM's default.
     */
    private static Month monthOf(String abbreviated) {
        String key = abbreviated.substring(0, 3).toUpperCase(Locale.ROOT);
        for (Month candidate : Month.values()) {
            if (candidate.name().startsWith(key)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Not a month: " + abbreviated);
    }

    /** "Aug" — the workbook's form. */
    private static String abbreviate(Month month) {
        String name = month.name();
        return name.charAt(0) + name.substring(1, 3).toLowerCase(Locale.ROOT);
    }
}
