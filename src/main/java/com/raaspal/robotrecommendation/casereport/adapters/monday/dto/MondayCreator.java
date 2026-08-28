package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The author of an update. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MondayCreator(String id, String name) {
}
