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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the rows of one board group, following the {@code items_page} cursor
 * until monday reports no more pages, and describes a board's columns and
 * groups so a column mapping can be written against real ids.
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
        } while (cursor != null && page < MAX_PAGES);

        boolean complete = cursor == null;
        if (!complete) {
            log.warn("Stopped reading monday board {} group {} after {} pages with a cursor still open; "
                    + "results are incomplete", boardId, groupId, MAX_PAGES);
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
