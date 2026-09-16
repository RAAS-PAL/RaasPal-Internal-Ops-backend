package com.raaspal.robotrecommendation.casereport.adapters.monday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItemPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the rows of one board group, following the {@code items_page} cursor
 * until monday reports no more pages; or the rows matching a server-side filter
 * across every group, see {@link #readFilteredItems}.
 *
 * <p>Comments are fetched in the same call as the rows. Querying them
 * separately would turn one request into one-per-row, which is the fastest way
 * to burn a daily API budget that is only 1,000 calls on most plans.
 */
@Slf4j
@Component
public class MondayBoardReader {

    /**
     * Rows matching a filter, from every group on the board.
     *
     * <p>Two queries because monday's cursor pagination changes shape after the
     * first page: {@code items_page} hangs off the board and takes the filter,
     * while every later page comes from the root-level {@code next_items_page},
     * which takes only the cursor - the filter is baked into it.
     *
     * <p>Replies are requested here and not in the group query below: the brand
     * export shows whole threads, and the pending reports never needed them.
     */
    private static final String FILTERED_ITEMS_QUERY = """
            query BoardFilteredItems(
              $boardId: ID!,
              $query: ItemsQuery,
              $columnIds: [String!],
              $limit: Int!,
              $updatesLimit: Int!
            ) {
              boards(ids: [$boardId]) {
                id
                items_page(limit: $limit, query_params: $query) {
                  cursor
                  items {
                    id
                    name
                    updated_at
                    group { id title }
                    column_values(ids: $columnIds) {
                      id
                      type
                      text
                    }
                    updates(limit: $updatesLimit) {
                      id
                      text_body
                      created_at
                      creator { id name }
                      replies {
                        id
                        text_body
                        created_at
                        creator { id name }
                      }
                    }
                  }
                }
              }
            }
            """;

    private static final String NEXT_FILTERED_ITEMS_QUERY = """
            query NextFilteredItems(
              $cursor: String!,
              $columnIds: [String!],
              $limit: Int!,
              $updatesLimit: Int!
            ) {
              next_items_page(limit: $limit, cursor: $cursor) {
                cursor
                items {
                  id
                  name
                  updated_at
                  group { id title }
                  column_values(ids: $columnIds) {
                    id
                    type
                    text
                  }
                  updates(limit: $updatesLimit) {
                    id
                    text_body
                    created_at
                    creator { id name }
                    replies {
                      id
                      text_body
                      created_at
                      creator { id name }
                    }
                  }
                }
              }
            }
            """;

    /** A status column's label set, to turn label text into the indexes a filter rule wants. */
    private static final String STATUS_LABELS_QUERY = """
            query StatusLabels($boardId: ID!, $columnIds: [String!]) {
              boards(ids: [$boardId]) {
                id
                columns(ids: $columnIds) { id settings_str }
              }
            }
            """;

    private static final String GROUP_ITEMS_QUERY = """
            query BoardGroupItems(
              $boardId: ID!,
              $groupIds: [String!],
              $columnIds: [String!],
              $limit: Int!,
              $updatesLimit: Int!,
              $cursor: String
            ) {
              boards(ids: [$boardId]) {
                id
                name
                groups(ids: $groupIds) {
                  id
                  title
                  items_page(limit: $limit, cursor: $cursor) {
                    cursor
                    items {
                      id
                      name
                      updated_at
                      group { id title }
                      column_values(ids: $columnIds) {
                        id
                        type
                        text
                      }
                      updates(limit: $updatesLimit) {
                        id
                        text_body
                        created_at
                        creator { id name }
                      }
                    }
                  }
                }
              }
            }
            """;

    /** Guards against a cursor that never returns null; 50 pages is far more than any group here. */
    private static final int MAX_PAGES = 50;

    private final MondayApiClient client;
    private final ObjectMapper objectMapper;
    private final int pageSize;
    private final int updatesPerItem;

    public MondayBoardReader(
            MondayApiClient client,
            ObjectMapper objectMapper,
            @Value("${app.monday.api.page-size:50}") int pageSize,
            @Value("${app.monday.api.updates-per-item:10}") int updatesPerItem) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.pageSize = pageSize;
        this.updatesPerItem = updatesPerItem;
    }

    /**
     * Every item in one group, with the requested columns and each item's most
     * recent comments.
     *
     * @param boardId   monday board id, e.g. "3451717331" (Cleaning Tickets)
     * @param groupId   group id, e.g. "new_group96592__1" (All Case)
     * @param columnIds the columns to fetch; ids are board-specific
     * @throws MondayApiException if the board or group is not visible to the configured token
     */
    public List<MondayItem> readGroupItems(String boardId, String groupId, List<String> columnIds) {
        List<MondayItem> allItems = new ArrayList<>();
        String cursor = null;
        int page = 0;

        do {
            Map<String, Object> variables = new HashMap<>();
            variables.put("boardId", boardId);
            variables.put("groupIds", List.of(groupId));
            variables.put("columnIds", columnIds);
            variables.put("limit", pageSize);
            variables.put("updatesLimit", updatesPerItem);
            variables.put("cursor", cursor);

            MondayItemPage itemPage = toItemPage(client.execute(GROUP_ITEMS_QUERY, variables), boardId, groupId);
            if (itemPage.items() != null) {
                allItems.addAll(itemPage.items());
            }
            cursor = itemPage.cursor();
            page++;
        } while (cursor != null && page < MAX_PAGES);

        if (cursor != null) {
            log.warn("Stopped reading monday board {} group {} after {} pages with a cursor still open; "
                    + "results are incomplete", boardId, groupId, MAX_PAGES);
        }
        log.info("Read {} items from monday board {} group {} in {} page(s)", allItems.size(), boardId, groupId, page);
        return allItems;
    }

    /**
     * One filter rule for {@link #readFilteredItems}. {@code compareValue} holds label
     * indexes for {@code any_of} on a status column, or one search string for
     * {@code contains_text}; monday accepts {@code "name"} as a column id.
     */
    public record FilterRule(String columnId, String operator, List<Object> compareValue) {

        public static FilterRule anyOf(String columnId, List<Integer> labelIndexes) {
            return new FilterRule(columnId, "any_of", new ArrayList<>(labelIndexes));
        }

        public static FilterRule containsText(String columnId, String text) {
            return new FilterRule(columnId, "contains_text", List.of(text));
        }

        Map<String, Object> toVariable() {
            Map<String, Object> rule = new HashMap<>();
            rule.put("column_id", columnId);
            rule.put("operator", operator);
            rule.put("compare_value", compareValue);
            return rule;
        }
    }

    /**
     * Every item on the board matching <em>any</em> of the rules, from every group.
     *
     * <p>One request for the whole answer instead of paging the board: the Delivery
     * Tickets board holds 5,400 rows across eight groups, and the brand this was
     * written for owns 76 of them. Reading it all to keep 76 cost 55 calls a night.
     *
     * @throws MondayApiException if the board is not visible to the configured token
     */
    public List<MondayItem> readFilteredItems(String boardId, List<FilterRule> rules, List<String> columnIds) {
        Map<String, Object> query = new HashMap<>();
        query.put("rules", rules.stream().map(FilterRule::toVariable).toList());
        query.put("operator", "or");

        Map<String, Object> variables = new HashMap<>();
        variables.put("boardId", boardId);
        variables.put("query", query);
        variables.put("columnIds", columnIds);
        variables.put("limit", pageSize);
        variables.put("updatesLimit", updatesPerItem);

        List<MondayItem> allItems = new ArrayList<>();
        MondayItemPage itemPage = toItemPage(
                boardItemsPage(client.execute(FILTERED_ITEMS_QUERY, variables), boardId), boardId);
        if (itemPage.items() != null) allItems.addAll(itemPage.items());
        String cursor = itemPage.cursor();
        int page = 1;

        while (cursor != null && page < MAX_PAGES) {
            Map<String, Object> next = new HashMap<>();
            next.put("cursor", cursor);
            next.put("columnIds", columnIds);
            next.put("limit", pageSize);
            next.put("updatesLimit", updatesPerItem);

            itemPage = toItemPage(client.execute(NEXT_FILTERED_ITEMS_QUERY, next).path("next_items_page"), boardId);
            if (itemPage.items() != null) allItems.addAll(itemPage.items());
            cursor = itemPage.cursor();
            page++;
        }

        if (cursor != null) {
            log.warn("Stopped reading filtered monday board {} after {} pages with a cursor still open; "
                    + "results are incomplete", boardId, MAX_PAGES);
        }
        log.info("Read {} filtered items from monday board {} in {} page(s)", allItems.size(), boardId, page);
        return allItems;
    }

    /**
     * The indexes of the given labels on one status column.
     *
     * <p>Filter rules on a status column take label <em>indexes</em>, not text, and the
     * index is whatever the person who built the dropdown happened to get. Read live
     * rather than configured so a re-ordered label cannot silently start matching the
     * wrong robots. Labels not on the column are logged and skipped.
     */
    public List<Integer> statusLabelIndexes(String boardId, String columnId, Collection<String> labels) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("boardId", boardId);
        variables.put("columnIds", List.of(columnId));

        JsonNode boards = client.execute(STATUS_LABELS_QUERY, variables).path("boards");
        if (!boards.isArray() || boards.isEmpty()) {
            throw new MondayApiException(
                    "monday board " + boardId + " was not found, or is not visible to the configured token");
        }
        JsonNode columns = boards.get(0).path("columns");
        if (!columns.isArray() || columns.isEmpty()) {
            throw new MondayApiException("monday column " + columnId + " was not found on board " + boardId);
        }

        Map<String, Integer> indexByLabel = new HashMap<>();
        try {
            JsonNode labelNode = objectMapper.readTree(columns.get(0).path("settings_str").asText("{}")).path("labels");
            labelNode.fieldNames().forEachRemaining(index ->
                    indexByLabel.put(labelNode.get(index).asText().trim(), Integer.parseInt(index)));
        } catch (Exception e) {
            throw new MondayApiException("Could not read the labels of column " + columnId + ": " + e.getMessage(), e);
        }

        List<Integer> indexes = new ArrayList<>();
        for (String label : labels) {
            Integer index = indexByLabel.get(label.trim());
            if (index == null) {
                log.warn("Status column {} on board {} has no label '{}'; skipping it", columnId, boardId, label);
            } else {
                indexes.add(index);
            }
        }
        return indexes;
    }

    private static JsonNode boardItemsPage(JsonNode data, String boardId) {
        JsonNode boards = data.path("boards");
        if (!boards.isArray() || boards.isEmpty()) {
            throw new MondayApiException(
                    "monday board " + boardId + " was not found, or is not visible to the configured token");
        }
        return boards.get(0).path("items_page");
    }

    private MondayItemPage toItemPage(JsonNode itemsPage, String boardId) {
        if (itemsPage.isMissingNode() || itemsPage.isNull()) {
            throw new MondayApiException("monday returned no items_page for board " + boardId);
        }
        try {
            return objectMapper.treeToValue(itemsPage, MondayItemPage.class);
        } catch (Exception e) {
            throw new MondayApiException("Failed to parse the monday items_page response: " + e.getMessage(), e);
        }
    }

    /**
     * Walks {@code data -> boards -> groups -> items_page} and converts it.
     *
     * <p>An empty {@code boards} array is how monday reports a board the token
     * cannot see - it is not an error response - so it is turned into one here
     * rather than being allowed to look like a board with no rows.
     */
    private MondayItemPage toItemPage(JsonNode data, String boardId, String groupId) {
        JsonNode boards = data.path("boards");
        if (!boards.isArray() || boards.isEmpty()) {
            throw new MondayApiException(
                    "monday board " + boardId + " was not found, or is not visible to the configured token");
        }

        JsonNode groups = boards.get(0).path("groups");
        if (!groups.isArray() || groups.isEmpty()) {
            throw new MondayApiException("monday group " + groupId + " was not found on board " + boardId);
        }

        JsonNode itemsPage = groups.get(0).path("items_page");
        if (itemsPage.isMissingNode() || itemsPage.isNull()) {
            throw new MondayApiException(
                    "monday returned no items_page for group " + groupId + " on board " + boardId);
        }

        try {
            return objectMapper.treeToValue(itemsPage, MondayItemPage.class);
        } catch (Exception e) {
            throw new MondayApiException("Failed to parse the monday items_page response: " + e.getMessage(), e);
        }
    }
}
