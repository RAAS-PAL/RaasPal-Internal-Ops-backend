package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One cell of a board row. {@code id} is the monday column id ("text0",
 * "status_17"), {@code text} the human-readable value, {@code value} the raw
 * JSON behind it, and {@code column} the column's definition when the query
 * asked for it (the case sync does, so the raw archive is self-describing).
 *
 * <p>Formula columns always return a null {@code text} over the API, so
 * anything derived from one has to be computed here instead.
 *
 * <p>{@code value} and {@code column} are requested by different callers - the
 * PM sync asks for one, the case sync for the other - so each is null whenever
 * the query that produced this cell did not ask for it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MondayColumnValue(
        String id, String type, String text, String value, MondayColumnRef column) {

    /** The column's display title, or null when the query did not request it. */
    public String title() {
        return column == null ? null : column.title();
    }

    /**
     * The raw JSON monday stores for this cell, or null when it was not requested.
     *
     * <p>Needed where {@code text} loses structure: a location cell's text is a
     * postal address with the coordinates dropped, and a timeline's text is two
     * dates glued with a dash. Both are unambiguous in {@code value}.
     */
    public String rawValue() {
        return value;
    }
}
