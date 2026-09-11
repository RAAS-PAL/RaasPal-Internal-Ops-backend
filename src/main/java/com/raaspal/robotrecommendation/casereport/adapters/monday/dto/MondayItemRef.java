package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A bare reference to another item - used for a subitem's parent. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MondayItemRef(String id, String name) {
}
