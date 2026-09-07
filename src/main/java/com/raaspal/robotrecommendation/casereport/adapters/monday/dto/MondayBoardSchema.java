package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A board's structure without its rows: every column and every group. This is
 * what the column mapping in {@code app.kpi.monday.boards[n].columns} is
 * written against, so it is exposed to the console for exactly that purpose.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MondayBoardSchema(
        String id,
        String name,
        @JsonProperty("items_count") Integer itemsCount,
        List<MondayColumnDef> columns,
        List<MondayGroup> groups
) {
}
