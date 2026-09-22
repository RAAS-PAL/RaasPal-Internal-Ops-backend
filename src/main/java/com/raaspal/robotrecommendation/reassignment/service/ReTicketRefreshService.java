package com.raaspal.robotrecommendation.reassignment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayApiClient;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayApiException;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.reassignment.config.ReAssignmentProperties;
import com.raaspal.robotrecommendation.reassignment.dto.ReDtos.RefreshResult;
import com.raaspal.robotrecommendation.reassignment.entity.ReAssignment;
import com.raaspal.robotrecommendation.reassignment.entity.ReEngineer;
import com.raaspal.robotrecommendation.reassignment.entity.ReTicket;
import com.raaspal.robotrecommendation.reassignment.repository.ReAssignmentRepository;
import com.raaspal.robotrecommendation.reassignment.repository.ReEngineerRepository;
import com.raaspal.robotrecommendation.reassignment.repository.ReTicketRepository;
import com.raaspal.robotrecommendation.reassignment.service.ReAssignmentEvaluator.Person;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the board's unfinished tickets from monday into {@code re_ticket}, then brings the
 * approved assignments up to date with what monday now shows.
 *
 * <p>One filtered read: every group, status not Done - about 700 items on the Cleaning
 * board, 7 pages. Groups are kept on each ticket and applied later, because tickets in the
 * finished groups often still carry a non-Done status (303 of them on 2026-09-22) and must
 * not count as work.
 *
 * <p>The RE column's raw {@code value} is read, not only its text: the text holds display
 * names, the value holds the monday user ids that engineers are linked by.
 *
 * <p>Reconciliation, for each current assignment:
 * <ul>
 *   <li>the approved engineer now appears in the RE column → CONFIRMED;</li>
 *   <li>the RE column shows only other people → SUPERSEDED (monday wins);</li>
 *   <li>the ticket is no longer open → ended, status kept (the work is over).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReTicketRefreshService {

    private static final String FIRST_PAGE = """
            query ReOpenTickets($board: ID!, $query: ItemsQuery, $columns: [String!]) {
              boards(ids: [$board]) {
                items_page(limit: 100, query_params: $query) {
                  cursor
                  items { id name updated_at group { title } column_values(ids: $columns) { id text value } }
                }
              }
            }
            """;

    private static final String NEXT_PAGE = """
            query ReOpenTicketsNext($cursor: String!, $columns: [String!]) {
              next_items_page(limit: 100, cursor: $cursor) {
                cursor
                items { id name updated_at group { title } column_values(ids: $columns) { id text value } }
              }
            }
            """;

    private static final int MAX_PAGES = 60;

    private final ReAssignmentProperties props;
    private final MondayApiClient monday;
    private final MondayBoardReader boardReader;
    private final ReTicketRepository tickets;
    private final ReAssignmentRepository assignments;
    private final ReEngineerRepository engineers;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    /** One refresh at a time; a second click while one runs is refused, not queued. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Instant lastRefreshAt;

    public Instant lastRefreshAt() {
        return lastRefreshAt;
    }

    public RefreshResult refresh() {
        if (!monday.isConfigured()) {
            throw new BadRequestException("The monday API token is not configured");
        }
        if (!running.compareAndSet(false, true)) {
            throw new BadRequestException("A refresh is already running");
        }
        long started = System.currentTimeMillis();
        try {
            List<JsonNode> items = read();
            // One transaction for the whole write: a failure halfway must not leave half
            // the tickets closed. TransactionTemplate, not @Transactional - this is a call
            // within the same bean, which would bypass the proxy.
            RefreshResult result = transactions.execute(status -> apply(items, started));
            lastRefreshAt = Instant.now();
            log.info("RE refresh: {} unfinished items, {} open in active groups, {} new, {} closed, "
                            + "{} confirmed, {} superseded in {} ms", result.seen(), result.openInActiveGroups(),
                    result.newTickets(), result.closed(), result.confirmed(), result.superseded(), result.durationMs());
            return result;
        } catch (MondayApiException e) {
            throw new BadRequestException("monday refresh failed: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** Every item whose status is not a closed status, all groups, all pages. */
    private List<JsonNode> read() {
        String board = props.getBoardId();
        String statusColumn = props.getColumns().getStatus();
        List<Integer> closedIdx = boardReader.statusLabelIndexes(board, statusColumn, props.getClosedStatuses());

        Map<String, Object> vars = new HashMap<>();
        vars.put("board", board);
        vars.put("columns", props.getColumns().all());
        if (!closedIdx.isEmpty()) {
            vars.put("query", Map.of("rules", List.of(Map.of(
                    "column_id", statusColumn, "compare_value", closedIdx, "operator", "not_any_of"))));
        }

        List<JsonNode> out = new ArrayList<>();
        JsonNode page = monday.execute(FIRST_PAGE, vars).path("boards").path(0).path("items_page");
        page.path("items").forEach(out::add);
        String cursor = page.path("cursor").isTextual() ? page.path("cursor").asText() : null;
        int pages = 1;
        while (cursor != null && pages < MAX_PAGES) {
            JsonNode next = monday.execute(NEXT_PAGE, Map.of("cursor", cursor, "columns", props.getColumns().all()))
                    .path("next_items_page");
            next.path("items").forEach(out::add);
            cursor = next.path("cursor").isTextual() ? next.path("cursor").asText() : null;
            pages++;
        }
        if (cursor != null) {
            throw new MondayApiException("Stopped after " + MAX_PAGES + " pages; refusing a partial refresh");
        }
        return out;
    }

    RefreshResult apply(List<JsonNode> items, long started) {
        String board = props.getBoardId();
        ReAssignmentProperties.Columns c = props.getColumns();
        Set<String> active = normSet(props.getActiveGroups());
        Set<String> closedStatuses = normSet(props.getClosedStatuses());

        Map<String, ReTicket> existing = new HashMap<>();
        tickets.findByBoardId(board).forEach(t -> existing.put(t.getItemId(), t));

        Instant now = Instant.now();
        Set<String> seen = new HashSet<>();
        int created = 0;
        int openActive = 0;
        for (JsonNode item : items) {
            String id = item.path("id").asText();
            seen.add(id);
            ReTicket t = existing.get(id);
            if (t == null) {
                t = ReTicket.builder().boardId(board).itemId(id).firstSeenAt(now).build();
                created++;
            }
            t.setItemName(item.path("name").asText(null));
            t.setGroupTitle(item.path("group").path("title").asText(null));
            t.setStatus(text(item, c.getStatus()));
            t.setSubStatus(text(item, c.getSubStatus()));
            t.setModelLabel(text(item, c.getModel()));
            t.setIssueLevel(text(item, c.getIssueLevel()));
            t.setCaseType(text(item, c.getCaseType()));
            t.setServiceMode(text(item, c.getServiceMode()));
            t.setSerialNumber(text(item, c.getSerial()));
            t.setCustomer(text(item, c.getCustomer()));
            t.setBranch(text(item, c.getBranch()));
            t.setMainIssue(text(item, c.getMainIssue()));
            t.setOpenDate(date(text(item, c.getOpenDate())));
            t.setActionDate(date(text(item, c.getActionDate())));
            t.setPeople(peopleJson(people(item, c.getPeople())));
            t.setMondayUpdatedAt(instant(item.path("updated_at").asText(null)));
            boolean open = !closedStatuses.contains(norm(t.getStatus()));
            t.setOpen(open);
            t.setLastSeenAt(now);
            tickets.save(t);
            if (open && active.contains(norm(t.getGroupTitle()))) openActive++;
        }

        // Anything monday no longer returns has been finished (or deleted/archived).
        int closed = 0;
        for (ReTicket t : existing.values()) {
            if (!seen.contains(t.getItemId()) && t.isOpen()) {
                t.setOpen(false);
                tickets.save(t);
                closed++;
            }
        }

        int[] reconciled = reconcile(board, now);
        return new RefreshResult(items.size(), openActive, created, closed, reconciled[0], reconciled[1],
                System.currentTimeMillis() - started);
    }

    /** {confirmed, superseded} */
    private int[] reconcile(String board, Instant now) {
        Map<UUID, ReEngineer> byId = new HashMap<>();
        engineers.findAll().forEach(e -> byId.put(e.getId(), e));
        Set<String> active = normSet(props.getActiveGroups());

        int confirmed = 0;
        int superseded = 0;
        for (ReAssignment a : assignments.findByBoardIdAndEndedAtIsNull(board)) {
            ReTicket t = tickets.findById(new ReTicket.Key(board, a.getItemId())).orElse(null);
            if (t == null || !t.isOpen() || !active.contains(norm(t.getGroupTitle()))) {
                a.setEndedAt(now);
                a.setEndedBy("system: ticket no longer open");
                assignments.save(a);
                continue;
            }
            List<Person> people = parsePeople(t.getPeople());
            if (people.isEmpty()) continue;
            ReEngineer e = byId.get(a.getEngineerId());
            boolean present = e != null && e.getMondayUserId() != null
                    && people.stream().anyMatch(p -> e.getMondayUserId().equals(p.id()));
            if (present) {
                if (ReAssignment.APPROVED.equals(a.getStatus())) {
                    a.setStatus(ReAssignment.CONFIRMED);
                    a.setConfirmedAt(now);
                    assignments.save(a);
                    confirmed++;
                }
            } else {
                a.setStatus(ReAssignment.SUPERSEDED);
                a.setEndedAt(now);
                a.setEndedBy("system: monday shows another RE");
                assignments.save(a);
                superseded++;
            }
        }
        return new int[]{confirmed, superseded};
    }

    /* ─── Parsing ─────────────────────────────────────────────────────────── */

    /**
     * People from the RE column: ids from the raw value ({@code personsAndTeams}), names from
     * the text in the same order. Teams are kept too - they are real assignments, just not
     * ones any engineer is linked to.
     */
    List<Person> people(JsonNode item, String column) {
        JsonNode cell = cell(item, column);
        if (cell == null) return List.of();
        String raw = cell.path("value").isTextual() ? cell.path("value").asText() : null;
        String text = cell.path("text").asText("");
        List<String> names = text.isBlank() ? List.of() : Arrays.stream(text.split(",")).map(String::trim).toList();
        List<Person> out = new ArrayList<>();
        if (raw != null && !raw.isBlank() && !"null".equals(raw)) {
            try {
                JsonNode entries = objectMapper.readTree(raw).path("personsAndTeams");
                int i = 0;
                for (JsonNode e : entries) {
                    String name = i < names.size() ? names.get(i) : null;
                    out.add(new Person(e.path("id").asText(), name));
                    i++;
                }
            } catch (Exception ex) {
                log.warn("Unreadable RE value on item {}: {}", item.path("id").asText(), ex.getMessage());
            }
        }
        return out;
    }

    public List<Person> parsePeople(String json) {
        try {
            List<Person> out = new ArrayList<>();
            for (JsonNode n : objectMapper.readTree(json == null ? "[]" : json)) {
                out.add(new Person(n.path("id").asText(), n.path("name").isNull() ? null : n.path("name").asText(null)));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public String peopleJson(List<Person> people) {
        try {
            return objectMapper.writeValueAsString(people);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static JsonNode cell(JsonNode item, String column) {
        for (JsonNode v : item.path("column_values")) {
            if (column.equals(v.path("id").asText())) return v;
        }
        return null;
    }

    private static String text(JsonNode item, String column) {
        JsonNode cell = cell(item, column);
        if (cell == null) return null;
        String t = cell.path("text").asText(null);
        return t == null || t.isBlank() ? null : t.trim();
    }

    private static LocalDate date(String text) {
        if (text == null) return null;
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    static Set<String> normSet(Collection<String> values) {
        Set<String> out = new HashSet<>();
        values.forEach(v -> out.add(norm(v)));
        return out;
    }

    static String norm(String s) {
        return ReAssignmentProperties.norm(s);
    }
}
