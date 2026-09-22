package com.raaspal.robotrecommendation.reassignment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayApiClient;
import com.raaspal.robotrecommendation.reassignment.config.ReAssignmentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The only place that changes the board: reads and sets the RE (People) column of one ticket.
 *
 * <p>Callers check the column right before writing, so an RE someone set on monday a minute
 * ago is never overwritten by an approval made from an older view of the queue.
 */
@Service
@RequiredArgsConstructor
public class ReMondayWriter {

    private static final String READ = """
            query ($ids: [ID!], $col: [String!]) {
              items(ids: $ids) { id column_values(ids: $col) { id value } }
            }""";

    private static final String WRITE = """
            mutation ($board: ID!, $item: ID!, $col: String!, $value: JSON!) {
              change_column_value(board_id: $board, item_id: $item, column_id: $col, value: $value) { id }
            }""";

    private final ReAssignmentProperties props;
    private final MondayApiClient monday;
    private final ObjectMapper objectMapper;

    public boolean enabled() {
        return props.getMondayWrite().isEnabled();
    }

    /** The monday user ids in the ticket's RE column right now. */
    public List<String> currentPeople(String itemId) {
        JsonNode data = monday.execute(READ, Map.of("ids", List.of(itemId), "col", List.of(column())));
        JsonNode items = data.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new IllegalStateException("monday no longer has ticket " + itemId);
        }
        List<String> out = new ArrayList<>();
        for (JsonNode cell : items.get(0).path("column_values")) {
            String raw = cell.path("value").isTextual() ? cell.path("value").asText() : null;
            if (raw == null || raw.isBlank() || "null".equals(raw)) continue;
            try {
                objectMapper.readTree(raw).path("personsAndTeams").forEach(p -> {
                    if ("person".equals(p.path("kind").asText("person"))) out.add(p.path("id").asText());
                });
            } catch (Exception e) {
                throw new IllegalStateException("Unreadable RE value on ticket " + itemId, e);
            }
        }
        return out;
    }

    /** Makes {@code mondayUserId} the ticket's only RE. */
    public void setPerson(String itemId, String mondayUserId) {
        write(itemId, "{\"personsAndTeams\":[{\"id\":" + Long.parseLong(mondayUserId) + ",\"kind\":\"person\"}]}");
    }

    /** Empties the ticket's RE column. */
    public void clear(String itemId) {
        write(itemId, "{\"personsAndTeams\":[]}");
    }

    private void write(String itemId, String value) {
        monday.execute(WRITE, Map.of("board", props.getBoardId(), "item", itemId, "col", column(), "value", value));
    }

    private String column() {
        return props.getColumns().getPeople();
    }
}
