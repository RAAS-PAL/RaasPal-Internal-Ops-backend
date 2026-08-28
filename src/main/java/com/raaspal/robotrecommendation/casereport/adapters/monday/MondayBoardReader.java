package com.raaspal.robotrecommendation.casereport.adapters.monday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * until monday reports no more pages.
 *
 * <p>Comments are fetched in the same call as the rows. Querying them
 * separately would turn one request into one-per-row, which is the fastest way
 * to burn a daily API budget that is only 1,000 calls on most plans.
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
