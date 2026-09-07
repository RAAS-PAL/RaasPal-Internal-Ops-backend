package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One cell of a board row. {@code id} is the monday column id ("text0",
 * "status_17"), {@code text} the human-readable value, and {@code column} the
 * column's definition when the query asked for it (the sync does, so the raw
 * archive is self-describing).
 *
 * <p>Formula columns always return a null {@code text} over the API, so
 * anything derived from one has to be computed here instead.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MondayColumnValue(String id, String type, String text, MondayColumnRef column) {

    /** The column's display title, or null when the query did not request it. */
    public String title() {
        return column == null ? null : column.title();
    }
}
