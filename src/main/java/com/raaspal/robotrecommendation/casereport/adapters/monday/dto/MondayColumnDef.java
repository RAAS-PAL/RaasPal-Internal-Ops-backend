package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One column of a board's schema: the id the API keys cells by, the title
 * people know it by, and the monday column type ("status", "date", "text").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MondayColumnDef(String id, String title, String type) {
}
