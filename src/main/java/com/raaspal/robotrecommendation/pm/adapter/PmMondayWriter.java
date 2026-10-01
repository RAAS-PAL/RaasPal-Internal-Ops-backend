package com.raaspal.robotrecommendation.pm.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * The only place the planner changes monday: reads one visit's Plan date and status,
 * and sets its Plan date.
 *
 * <p>Reading and writing are separate so the caller can compare what monday holds now
 * with what the person was looking at, and stop rather than overwrite a date someone
 * changed on monday since the planner last synced. The status is read live for the
 * same reason: whether a visit is completed is decided by monday, not by the mirror.
 */
@Component
@RequiredArgsConstructor
public class PmMondayWriter {

    private static final String READ = """
            query ($ids: [ID!], $col: [String!]) {
              items(ids: $ids) { id board { id } column_values(ids: $col) { id text value } }
            }""";

    private static final String WRITE = """
            mutation ($board: ID!, $item: ID!, $col: String!, $value: JSON!) {
              change_column_value(board_id: $board, item_id: $item, column_id: $col, value: $value) { id }
            }""";

    private final MondayApiClient monday;
    private final ObjectMapper objectMapper;

    /**
     * A visit as monday holds it right now.
     *
     * @param time   kept so that moving a visit does not drop a time someone set; null when there is none
     * @param status the status label as monday shows it; null when empty or not asked for
     */
    public record Snapshot(LocalDate planDate, String time, String status) {
    }

    /**
     * The visit's plan date and status on monday right now.
     *
     * @param statusColumnId null to skip the status
     */
    public Snapshot read(String subitemBoardId, String itemId, String dateColumnId, String statusColumnId) {
        List<String> columns = statusColumnId == null ? List.of(dateColumnId) : List.of(dateColumnId, statusColumnId);
        JsonNode data = monday.execute(READ, Map.of("ids", List.of(itemId), "col", columns));
        JsonNode items = data.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new IllegalStateException("monday no longer has this visit (item " + itemId + ")");
        }
        JsonNode item = items.get(0);
        String board = item.path("board").path("id").asText("");
        if (!board.isEmpty() && !board.equals(subitemBoardId)) {
            // A wrong board here would mean writing a date into some other board's column.
            throw new IllegalStateException("monday item " + itemId + " is on board " + board
                    + ", not the PM subitem board " + subitemBoardId);
        }
        DateCell date = DateCell.EMPTY;
        String status = null;
        for (JsonNode cell : item.path("column_values")) {
            String id = cell.path("id").asText();
            if (id.equals(dateColumnId)) {
                date = parseDate(itemId, cell.path("value"));
            } else if (id.equals(statusColumnId)) {
                String text = cell.path("text").asText("");
                status = text.isBlank() ? null : text.trim();
            }
        }
        return new Snapshot(date.date(), date.time(), status);
    }

    /**
     * Sets the visit's plan date, keeping {@code time} when there is one. A null date
     * empties the cell, which only an undo does: back to a visit that had no date.
     */
    public void write(String subitemBoardId, String itemId, String columnId, LocalDate date, String time) {
        ObjectNode value = objectMapper.createObjectNode();
        if (date != null) {
            value.put("date", date.toString());
            if (time != null) {
                value.put("time", time);
            }
        }
        monday.execute(WRITE, Map.of("board", subitemBoardId, "item", itemId, "col", columnId,
                "value", value.toString()));
    }

    private record DateCell(LocalDate date, String time) {
        static final DateCell EMPTY = new DateCell(null, null);
    }

    /** monday sends a date cell's value as a JSON string, or null when the cell is empty. */
    private DateCell parseDate(String itemId, JsonNode rawValue) {
        String raw = rawValue.isTextual() ? rawValue.asText() : null;
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return DateCell.EMPTY;
        }
        try {
            JsonNode value = objectMapper.readTree(raw);
            String date = value.path("date").asText("");
            String time = value.path("time").isTextual() && !value.path("time").asText().isBlank()
                    ? value.path("time").asText()
                    : null;
            return new DateCell(date.isEmpty() ? null : LocalDate.parse(date), time);
        } catch (DateTimeParseException | JsonProcessingException e) {
            throw new IllegalStateException("Unreadable plan date on monday item " + itemId, e);
        }
    }
}
