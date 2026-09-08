package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One column of a board's schema: the id the API keys cells by, the title
 * people know it by, and the monday column type ("status", "date", "text").
 *
 * <p>{@code settingsStr} carries the column's own configuration as a JSON
 * string — for a status or dropdown column, the list of labels it allows. Those
 * labels are board <em>configuration</em>, not ticket content, which is what
 * makes it possible to write a mapping (which status means "closed", which
 * value means a cleaning robot) without ever reading a customer's rows.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MondayColumnDef(
        String id,
        String title,
        String type,
        @JsonProperty("settings_str") String settingsStr
) {
}
