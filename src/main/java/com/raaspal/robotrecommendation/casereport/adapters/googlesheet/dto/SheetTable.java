package com.raaspal.robotrecommendation.casereport.adapters.googlesheet.dto;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A tab read as a table: its header row, and every non-empty row under it.
 *
 * <p>Columns are found by header, never by position. The AOT sheet has hidden columns
 * and people insert new ones; a column index that was right last week would silently
 * read a neighbouring column this week.
 */
public record SheetTable(List<String> headers, List<SheetRow> rows) {

    /**
     * The header as the sheet spells it, matched ignoring case and runs of whitespace -
     * so a configured {@code Issue Date} still finds {@code Issue  date } after someone
     * retypes it.
     */
    public Optional<String> header(String wanted) {
        if (wanted == null || wanted.isBlank()) return Optional.empty();
        String key = normalise(wanted);
        return headers.stream().filter(h -> normalise(h).equals(key)).findFirst();
    }

    public static String normalise(String text) {
        return text.replace(' ', ' ').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
