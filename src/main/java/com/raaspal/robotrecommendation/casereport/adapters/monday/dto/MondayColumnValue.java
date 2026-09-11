package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One cell of a board row. {@code id} is the monday column id ("text0",
 * "status_17"), {@code text} the human-readable value.
 *
 * <p>Formula columns always return a null {@code text} over the API, so
 * anything derived from one has to be computed here instead.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MondayColumnValue(String id, String type, String text, String value) {

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
