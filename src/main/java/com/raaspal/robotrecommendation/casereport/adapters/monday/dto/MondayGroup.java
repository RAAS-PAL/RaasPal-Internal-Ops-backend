package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A board group, e.g. "All Case". */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MondayGroup(String id, String title) {
}
