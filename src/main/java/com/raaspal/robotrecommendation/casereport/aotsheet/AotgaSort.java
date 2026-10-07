package com.raaspal.robotrecommendation.casereport.aotsheet;

import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;

/**
 * The order the AOT tab's table is in, so the Excel comes out in the order on the screen.
 * The console sorts by the same rules (frontend lib/aotgaSort.ts {@code sortCases}):
 * text compared lower-cased, code unit by code unit; a case with no value for the column
 * last whichever way round; ties by ticket number, then sheet row.
 *
 * @param key        the column
 * @param descending largest, latest or Z first
 */
public record AotgaSort(Key key, boolean descending) {

    /** Newest issue date first: what the tab opens on. */
    public static final AotgaSort DEFAULT = new AotgaSort(Key.ISSUED, true);

    public enum Key {
        TICKET("AOT Ticket no.", "A to Z", "Z to A"),
        SITE("Location", "A to Z", "Z to A"),
        ROBOT("Robot", "A to Z", "Z to A"),
        SERIAL("S/N", "A to Z", "Z to A"),
        ISSUED("Issue Date", "oldest first", "newest first"),
        DAYS("Days", "fewest first", "most first"),
        STAGE("Stage", "earliest stage first", "latest stage first"),
        SENT("Sent to Manufacturer", "not yet first", "sent first");

        final String header;
        final String ascending;
        final String descending;

        Key(String header, String ascending, String descending) {
            this.header = header;
            this.ascending = ascending;
            this.descending = descending;
        }
    }

    /** Reads the console's {@code sort} and {@code dir} parameters; anything else is the default. */
    public static AotgaSort of(String key, String dir) {
        if (key == null || key.isBlank()) return DEFAULT;
        try {
            return new AotgaSort(Key.valueOf(key.trim().toUpperCase(Locale.ROOT)), !"asc".equalsIgnoreCase(dir));
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    /** "Issue Date, newest first", for the workbook's heading. */
    public String describe() {
        return key.header + ", " + (descending ? key.descending : key.ascending);
    }

    public Comparator<AotgaTracker.Case> comparator() {
        Comparator<AotgaTracker.Case> byKey = switch (key) {
            case TICKET -> by(c -> lower(c.ticketNo()));
            case SITE -> by(c -> lower(c.site()));
            case ROBOT -> by(c -> lower(c.robot()));
            case SERIAL -> by(c -> lower(c.serial()));
            case ISSUED -> by(AotgaTracker.Case::issueDate);
            case DAYS -> by(AotgaTracker.Case::days);
            case STAGE -> by(c -> c.stage() == null ? null : c.stage().ordinal());
            case SENT -> by(AotgaSort::sentRank);
        };
        return byKey
                .thenComparing(c -> lower(c.ticketNo()), Comparator.nullsLast(Comparator.<String>naturalOrder()))
                .thenComparing(AotgaTracker.Case::sheetRow, Comparator.nullsLast(Comparator.<Integer>naturalOrder()));
    }

    /** One column's values in this direction, with the cases that have none last either way. */
    private <T extends Comparable<? super T>> Comparator<AotgaTracker.Case> by(Function<AotgaTracker.Case, T> value) {
        Comparator<T> order = descending ? Comparator.<T>reverseOrder() : Comparator.<T>naturalOrder();
        return Comparator.comparing(value, Comparator.nullsLast(order));
    }

    /** Not yet sent before sent; a case whose old part is not back has nothing to sort by. */
    private static Integer sentRank(AotgaTracker.Case c) {
        if (c.stage() == AotgaTracker.Stage.CLAIMED) return 2;
        if (c.stage() == AotgaTracker.Stage.OLD_PART_BACK) return 1;
        return null;
    }

    private static String lower(String s) {
        return s == null || s.isBlank() ? null : s.toLowerCase(Locale.ROOT);
    }
}
