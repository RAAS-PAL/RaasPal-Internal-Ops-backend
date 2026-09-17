package com.raaspal.robotrecommendation.casereport.adapters.monday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayBoardRef;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayBoardSchema;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayGroupRead;
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
 * across every group, see {@link #readFilteredItems}. It also describes a
 * board's columns and groups, so a column mapping can be written against real
 * ids rather than guessed.
 *
 * <p>Comments can be fetched in the same call as the rows. Querying them
 * separately would turn one request into one-per-row, which is the fastest way
 * to burn a daily API budget that is only 1,000 calls on most plans. The KPI
 * sync does not need them at all and switches them off, which keeps a full read
 * of a 1,000-ticket archive group inside monday's complexity budget.
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
              $withUpdates: Boolean!,
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
                        column { id title }
                      }
                      updates(limit: $updatesLimit) @include(if: $withUpdates) {
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

    private static final String BOARD_SCHEMA_QUERY = """
            query BoardSchema($boardId: ID!) {
              boards(ids: [$boardId]) {
                id
                name
                items_count
                columns { id title type settings_str }
                groups { id title }
              }
            }
            """;

    private static final String BOARD_LIST_QUERY = """
            query BoardList($limit: Int!, $page: Int!) {
              boards(limit: $limit, page: $page, order_by: created_at) {
                id
                name
                state
              }
            }
            """;

    /**
     * Guards against a cursor that never returns null. Configurable because the
     * original 50 was wrong: the Delivery board's "DONE-Ticket" archive alone holds
     * more than 2,500 tickets, and the read was silently cut off at page 50 - the
     * KPI would have been computed on whichever half of the archive monday happened
     * to return first. At the default page size the new default allows 50,000 rows
     * per group, which is beyond any board here; a genuinely runaway cursor still
     * stops, and the read is still flagged incomplete when it does.
     */
    private final int maxPages;

    private final MondayApiClient client;
    private final ObjectMapper objectMapper;
    private final int pageSize;
    private final int updatesPerItem;

    public MondayBoardReader(
            MondayApiClient client,
            ObjectMapper objectMapper,
            @Value("${app.monday.api.page-size:100}") int pageSize,
            @Value("${app.monday.api.updates-per-item:10}") int updatesPerItem,
            @Value("${app.monday.api.max-pages-per-group:500}") int maxPages) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.pageSize = pageSize;
        this.updatesPerItem = updatesPerItem;
        this.maxPages = maxPages;
    }

    /**
     * Every item in one group, with the requested columns and each item's most
     * recent comments.
     *
     * @param boardId   monday board id, e.g. "3451717331" (Cleaning Tickets)
     * @param groupId   group id, e.g. "new_group96592__1" (All Case)
     * @param columnIds the columns to fetch; ids are board-specific. Empty means every column.
     * @throws MondayApiException if the board or group is not visible to the configured token
     */
    public List<MondayItem> readGroupItems(String boardId, String groupId, List<String> columnIds) {
        return readGroup(boardId, groupId, columnIds, true).items();
    }

    /**
     * Every item in one group, and whether the read was complete.
     *
     * @param columnIds      the columns to fetch. Empty means every column on the board.
     * @param includeUpdates whether to fetch each item's comment thread; false keeps
     *                       a large archive group well inside monday's complexity budget
     */
    public MondayGroupRead readGroup(String boardId, String groupId, List<String> columnIds, boolean includeUpdates) {
        List<MondayItem> allItems = new ArrayList<>();
        String cursor = null;
        int page = 0;

        do {
            Map<String, Object> variables = new HashMap<>();
            variables.put("boardId", boardId);
            variables.put("groupIds", List.of(groupId));
            // Leaving the variable out entirely (rather than sending null) is what
            // makes monday return every column; an empty array returns none.
            if (columnIds != null && !columnIds.isEmpty()) {
                variables.put("columnIds", columnIds);
            }
            variables.put("limit", pageSize);
            variables.put("updatesLimit", updatesPerItem);
            variables.put("withUpdates", includeUpdates);
            variables.put("cursor", cursor);

            MondayItemPage itemPage = toItemPage(client.execute(GROUP_ITEMS_QUERY, variables), boardId, groupId);
            if (itemPage.items() != null) {
                allItems.addAll(itemPage.items());
            }
            cursor = itemPage.cursor();
            page++;
            // Per page, so a slow board shows progress in the log instead of looking
            // hung. A full first sync of an archive group is dozens of pages.
            log.info("monday board {} group {}: page {} read, {} items so far{}",
                    boardId, groupId, page, allItems.size(), cursor == null ? " (last page)" : "");
        } while (cursor != null && page < maxPages);

        boolean complete = cursor == null;
        if (!complete) {
            log.warn("Stopped reading monday board {} group {} after {} pages with a cursor still open; "
                    + "results are incomplete - raise app.monday.api.max-pages-per-group", boardId, groupId, maxPages);
        }
        log.info("Read {} items from monday board {} group {} in {} page(s)", allItems.size(), boardId, groupId, page);
        return new MondayGroupRead(allItems, complete);
    }

    /**
     * Every board the token can see: id, name and state only, never a row. Used
     * to find a board's id without asking someone to read it out of a URL.
     */
    public List<MondayBoardRef> listBoards(int limit) {
        List<MondayBoardRef> boards = new ArrayList<>();
        for (int page = 1; page <= 20; page++) {
            JsonNode data = client.execute(BOARD_LIST_QUERY, Map.of("limit", limit, "page", page));
            JsonNode node = data.path("boards");
            if (!node.isArray() || node.isEmpty()) {
                break;
            }
            for (JsonNode board : node) {
                boards.add(new MondayBoardRef(
                        board.path("id").asText(null),
                        board.path("name").asText(null),
                        board.path("state").asText(null)));
            }
            if (node.size() < limit) {
                break;
            }
        }
        return boards;
    }

    /**
     * The board's columns and groups, without any rows. One call, so it is safe
     * to use from a console screen as well as at the start of a sync.
     *
     * @throws MondayApiException if the board is not visible to the configured token
     */
    public MondayBoardSchema describeBoard(String boardId) {
        JsonNode data = client.execute(BOARD_SCHEMA_QUERY, Map.of("boardId", boardId));
        JsonNode boards = data.path("boards");
        if (!boards.isArray() || boards.isEmpty()) {
            throw new MondayApiException(
                    "monday board " + boardId + " was not found, or is not visible to the configured token");
        }
        try {
            return objectMapper.treeToValue(boards.get(0), MondayBoardSchema.class);
        } catch (Exception e) {
            throw new MondayApiException("Failed to parse the monday board schema response: " + e.getMessage(), e);
        }
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

        while (cursor != null && page < maxPages) {
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
                    + "results are incomplete - raise app.monday.api.max-pages-per-group", boardId, maxPages);
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
