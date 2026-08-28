package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One page of board items. A null {@code cursor} means this was the last page;
 * otherwise pass it back to fetch the next one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MondayItemPage(String cursor, List<MondayItem> items) {
}
