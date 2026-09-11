package com.raaspal.robotrecommendation.pm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayApiClient;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayApiException;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItemPage;
import com.raaspal.robotrecommendation.pm.config.PmMondayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads whole PM boards, and the subitem boards that hold the actual visits.
 *
 * <p>Separate from {@code MondayBoardReader} rather than an extra method on it,
 * for three reasons that all pull the same way: this reads a board rather than
 * one group (a PM board's groups are contract states like "หมดสัญญา", and the
 * planner wants all of them); it asks for {@code parent_item}, which only
 * subitems have; and it deliberately does not fetch comment threads, which the
 * case reader always does. Bending the existing reader to cover both would have
 * meant three flags and a query that does none of it plainly.
 *
 * <p>Paging is sized for the data: the Cleaning subitem board alone is about
 * 2,800 rows, so the case reader's 50-per-page and 50-page ceiling would stop
 * silently at half of it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmBoardReader {

    private static final String BOARD_ITEMS_QUERY = """
            query PmBoardItems(
              $boardId: ID!,
              $columnIds: [String!],
              $limit: Int!,
              $cursor: String
            ) {
              boards(ids: [$boardId]) {
                id
                name
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
                      value
                    }
                  }
                }
              }
            }
            """;

    private static final String SUBITEM_BOARD_QUERY = """
            query PmSubitemBoardItems(
              $boardId: ID!,
              $columnIds: [String!],
              $limit: Int!,
              $cursor: String
            ) {
              boards(ids: [$boardId]) {
                id
                name
                items_page(limit: $limit, cursor: $cursor) {
                  cursor
                  items {
                    id
                    name
                    updated_at
                    parent_item { id }
                    column_values(ids: $columnIds) {
                      id
                      type
                      text
                      value
                    }
                  }
                }
              }
            }
            """;

    private final MondayApiClient client;
    private final ObjectMapper objectMapper;
    private final PmMondayProperties properties;

    /** Every item on a parent PM board, across all groups. */
    public List<MondayItem> readBoardItems(String boardId, List<String> columnIds) {
        return read(BOARD_ITEMS_QUERY, boardId, columnIds, "board");
    }

    /** Every subitem on a subitem board, each carrying its parent's id. */
    public List<MondayItem> readSubitems(String subitemBoardId, List<String> columnIds) {
        return read(SUBITEM_BOARD_QUERY, subitemBoardId, columnIds, "subitem board");
    }

    private List<MondayItem> read(String query, String boardId, List<String> columnIds, String what) {
        List<MondayItem> all = new ArrayList<>();
        String cursor = null;
        int page = 0;

        do {
            Map<String, Object> variables = new HashMap<>();
            variables.put("boardId", boardId);
            variables.put("columnIds", columnIds);
            variables.put("limit", properties.getPageSize());
            variables.put("cursor", cursor);

            MondayItemPage itemPage = toItemPage(client.execute(query, variables), boardId);
            if (itemPage.items() != null) {
                all.addAll(itemPage.items());
            }
            cursor = itemPage.cursor();
            page++;
        } while (cursor != null && page < properties.getMaxPages());

        if (cursor != null) {
            // Loud, because the symptom is a planner that looks fine and is short
            // several hundred visits.
            log.warn("Stopped reading monday {} {} after {} pages with a cursor still open; results are INCOMPLETE",
                    what, boardId, properties.getMaxPages());
        }
        log.info("Read {} items from monday {} {} in {} page(s)", all.size(), what, boardId, page);
        return all;
    }

    private MondayItemPage toItemPage(JsonNode data, String boardId) {
        JsonNode boards = data.path("boards");
        if (!boards.isArray() || boards.isEmpty()) {
            throw new MondayApiException(
                    "monday board " + boardId + " was not found, or is not visible to the configured token");
        }
        JsonNode itemsPage = boards.get(0).path("items_page");
        if (itemsPage.isMissingNode() || itemsPage.isNull()) {
            throw new MondayApiException("monday returned no items_page for board " + boardId);
        }
        try {
            return objectMapper.treeToValue(itemsPage, MondayItemPage.class);
        } catch (Exception e) {
            throw new MondayApiException("Failed to parse the monday items_page response: " + e.getMessage(), e);
        }
    }
}
