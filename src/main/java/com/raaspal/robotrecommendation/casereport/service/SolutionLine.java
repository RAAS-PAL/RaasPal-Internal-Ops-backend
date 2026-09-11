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
     * <p>Group 1 and 2 are the day numbers, 3 the month, 4 the phrase. The lookahead that
     * ends the phrase matches either form of a following date: {@code 03-Sep} or
     * {@code 08-09 Sep}.
     */
    private static final Pattern RANGE = Pattern.compile(
            "\\b(\\d{1,2})-(\\d{1,2}) (" + MON + ")\\b\\s*(.*?)"
                    + "(?=\\s+\\d{1,2}-(?:\\d{1,2} )?(?:" + MON + ")\\b|$)");

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
     * dates with one description.
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
            String monthName = m.group(3);
            String phrase = m.group(4).strip();

            if (from <= to) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }

            Month month = monthOf(monthName);
            Month previous = month.minus(1);
            // January's previous month is December of the year before.
            int previousYear = month == Month.JANUARY ? year - 1 : year;
            int lastDay = previous.length(Year.isLeap(previousYear));

            String fixed = String.format("%02d-%02d %s %s 01-%02d %s %s",
                    from, lastDay, abbreviate(previous), phrase,
                    to, monthName, phrase).strip();

            m.appendReplacement(out, Matcher.quoteReplacement(fixed));
        }
        m.appendTail(out);
        return out.toString();
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
