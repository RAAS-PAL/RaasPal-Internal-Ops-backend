package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/** One row on a board, with the cells that were requested and its comment thread. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MondayItem(
        String id,
        String name,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        MondayGroup group,
        @JsonProperty("column_values") List<MondayColumnValue> columnValues,
        List<MondayUpdate> updates,
        /**
         * The owning item when this row is a subitem, otherwise null. Only the PM
         * reader requests it; board reads that do not ask for it get null here.
         */
        @JsonProperty("parent_item") MondayItemRef parentItem
) {

    /**
     * The text of one column, or null when it is absent, blank, or a formula.
     *
     * <p>Column ids mean different things on different boards - {@code text} is
     * Main Issue on Cleaning Tickets but Solution on Delivery Tickets - so the
     * id passed here must belong to the board this item came from.
     */
    public String columnText(String columnId) {
        if (columnValues == null) {
            return null;
        }
        return columnValues.stream()
                .filter(value -> columnId.equals(value.id()))
                .map(MondayColumnValue::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null);
    }

    /** The most recent comment, or null when the item has none. */
    public MondayUpdate latestUpdate() {
        return updates == null || updates.isEmpty() ? null : updates.get(0);
    }

    /** The raw JSON of one column, for cells whose text drops structure. */
    public String columnRawValue(String columnId) {
        if (columnValues == null) {
            return null;
        }
        return columnValues.stream()
                .filter(value -> columnId.equals(value.id()))
                .map(MondayColumnValue::value)
                .filter(value -> value != null && !value.isBlank() && !"null".equals(value))
                .findFirst()
                .orElse(null);
    }

    /** The parent item's id, or null when this row is not a subitem. */
    public String parentItemId() {
        return parentItem == null ? null : parentItem.id();
    }

    /** The group title, or null when the group was not requested. */
    public String groupTitle() {
        return group == null ? null : group.title();
    }
}
