package com.raaspal.robotrecommendation.casereport.service;

import java.util.List;
import java.util.Locale;

/**
 * Which cleaning-board tickets belong to the airports (AOTGA).
 *
 * <p>Asked from two directions, which is the whole reason it lives here rather than
 * inside either caller: {@link CleaningPendingReportGenerator} asks it to keep airport
 * tickets <em>off</em> the Cleaning sheet, and the AOTGA sheet asks the same question to
 * put them <em>on</em> its own. Kept as two lists they would eventually drift, and a
 * ticket matching neither would vanish from both sheets with nothing to notice it by —
 * the failure is silent, which is what makes it worth one class.
 *
 * <p>Matched as lower-cased substrings. Both spellings of ท่าอากาศยาน are on the board
 * (one is missing a letter), and the Project tag is blank on many tickets, so the branch
 * name is checked too.
 *
 * <h2>The two entry points read different fields, on purpose</h2>
 *
 * <p>{@link #matches} reads the Project tag and branch name. {@link #matchesIncludingName}
 * reads the ticket's own name as well, because the RE team writes the
 * {@code AOTGA-BKK M75 : …} prefix there. Only the AOTGA sheet uses the wider one; the
 * Cleaning sheet's exclusion was deliberately left as it was, so that adding AOTGA takes
 * no rows off a report the team already reads each morning.
 *
 * <p>The asymmetry has a known consequence, accepted rather than overlooked: a ticket
 * whose <em>only</em> airport marker is its name is claimed by the AOTGA sheet and is not
 * excluded from the Cleaning one, so it appears on both. That is the direction to fail in
 * — a duplicated row is visible and a reviewer can act on it, where a row that matched
 * neither sheet would simply be gone. Widening {@link #matches} to the name too would
 * close it, at the cost of shrinking the live Cleaning sheet.
 */
public final class AirportTickets {

    /**
     * How an airport ticket is recognised.
     *
     * <p>AOT runs six airports; only Suvarnabhumi, Don Mueang and Mae Fah Luang are named
     * here. Chiang Mai, Phuket and Hat Yai are caught only by the generic words or by an
     * AOTGA tag — bare city names cannot be added, because the board also carries
     * "Makroหาดใหญ่", which would then be misfiled as an airport.
     */
    private static final List<String> AIRPORTS = List.of(
            "aotga", "aot ", "ท่าอากาศยาน", "ท่าอาศยาน", "สนามบิน", "สุวรรณภูมิ", "ดอนเมือง",
            "แม่ฟ้าหลวง");

    private AirportTickets() {
    }

    /**
     * Whether the Project tag or branch name marks this ticket as an airport's.
     *
     * <p>What the Cleaning sheet excludes on. Does not read the ticket name — see the
     * class note.
     */
    public static boolean matches(String projectTag, String branchName) {
        return containsAirport(projectTag, branchName);
    }

    /**
     * The same question, also reading the ticket's own name.
     *
     * <p>What the AOTGA sheet includes on, because the {@code AOTGA-BKK M75 : …} prefix
     * the RE team was asked to write lives in the name, not in a column.
     */
    public static boolean matchesIncludingName(String itemName, String projectTag, String branchName) {
        return containsAirport(itemName, projectTag, branchName);
    }

    /**
     * Joins the fields with single spaces — never a trailing one, since {@code "aot "}
     * carries a significant space and a trailing separator would make a branch ending in
     * "aot" match.
     */
    private static boolean containsAirport(String... fields) {
        StringBuilder haystack = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                haystack.append(' ');
            }
            haystack.append(fields[i] == null ? "" : fields[i]);
        }
        String needle = haystack.toString().toLowerCase(Locale.ROOT);
        return AIRPORTS.stream().anyMatch(needle::contains);
    }
}
