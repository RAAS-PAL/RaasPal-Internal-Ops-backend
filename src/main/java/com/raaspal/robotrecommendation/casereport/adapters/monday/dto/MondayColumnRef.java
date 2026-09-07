package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The column a cell belongs to, as nested under {@code column_values.column}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MondayColumnRef(String id, String title) {
}
