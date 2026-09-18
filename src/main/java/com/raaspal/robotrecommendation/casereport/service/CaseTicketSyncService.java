package com.raaspal.robotrecommendation.casereport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayUpdate;
import com.raaspal.robotrecommendation.casereport.entity.*;
import com.raaspal.robotrecommendation.casereport.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Records what the boards look like today, so tomorrow can still see it.
 *
 * <p>The reports read monday live, which is fine for today and useless for any other day:
 * the team edits the boards continuously, so yesterday cannot be re-read. This writes a
 * daily record instead. Two things come out of it that a live read cannot give:
 *
 * <ul>
 *   <li><b>Status history.</b> The board holds only a ticket's current status, so the day
 *       it moved is known only if a sync was there to see it. One row per change, in
 *       {@code case_ticket_status_history}. (It is not the report's Solution column, which
 *       is written from the comment thread; see {@code MkPendingReportGenerator}.)</li>
 *   <li><b>Comment threads.</b> Part Received, Required Part and Waiting have no board
 *       column at all — those three were verified empty on every live cleaning ticket — so
 *       whatever fills them has to come from the comments stored here.</li>
 * </ul>
 *
 * <p>⚠️ <strong>History only accrues from the first run.</strong> Nothing here reconstructs
 * the past: a day that passed before this ran has no snapshot and never will. That is the
 * argument for starting it sooner rather than when the rest of the feature is finished.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseTicketSyncService {

    /** Cleaning Tickets / All Case. */
    public static final String CLEANING_BOARD = "3451717331";
    private static final String CLEANING_GROUP = "new_group96592__1";

    /** Delivery Tickets / All Case. */
    public static final String DELIVERY_BOARD = "1647612496";
    private static final String DELIVERY_GROUP = "group_title";

    /**
     * The business day a snapshot is filed under.
     *
     * <p>Bangkok, not the server's zone: a sync at 07:00 Bangkok is this morning's
     * snapshot, and on a UTC clock that is still yesterday — which would file two
     * snapshots under one date and trip {@code uq_case_ticket_status_day}.
     */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    /**
     * Columns worth mapping onto their own fields. Everything else still arrives, in
     * {@code raw_columns}, so a column nobody thought to map is already there rather than
     * needing a re-sync that cannot recover what has since changed.
     */
    private static final List<String> CLEANING_COLUMNS = List.of(
            "asset_owner3__1", "text6", "status_17", "text0", "text", "long_text",
            "date8", "date_1", "status", "status7", "status_1");

    /**
     * The last row is unmapped and lands only in {@code raw_columns}: Root Cause, RE
     * owner, Type of Case, Level, Under Warranty, Channel. The brand analytics read them
     * from there. Requested by both the open-group sync and the brand sync so whichever
     * ran last leaves the same raw payload behind.
     */
    public static final List<String> DELIVERY_COLUMNS = List.of(
            "asset_owner", "text6", "tags2", "status_139", "tags42",
            "main_issue_key_word3", "text", "date5", "date_18",
            "color_mm6mwh74", "status", "status_1",
            "status_10", "color_mksn4t14", "color_mkyh88bs", "status_136", "text23", "status_169");

    /** The open group on the delivery board; a ticket anywhere else has left it. */
    public static final String DELIVERY_OPEN_GROUP = DELIVERY_GROUP;

    private final MondayBoardReader boardReader;
    private final CaseTicketRepository tickets;
    private final CaseTicketUpdateRepository updates;
    private final CaseTicketStatusHistoryRepository history;
    private final ObjectMapper objectMapper;

    /** What one board's sync did, for the log and the trigger endpoint's response. */
    public record SyncResult(String boardId, int seen, int created, int updated,
                             int closed, int newComments, int statusChanges) {

    }

    /**
     * One board's sync parameters.
     *
     * <p>Public so {@code CaseSyncCoordinator} can drive them. `syncAll` used to live on
     * this class and call {@link #sync} directly, which silently did nothing useful: a
     * self-invocation bypasses the Spring proxy, so {@code @Transactional} never applied
     * and the first flush failed with "No EntityManager with actual transaction". Driving
     * it from another bean means each board genuinely gets its own transaction, which is
     * the property that matters -- a failure on cleaning must not roll back delivery.
     */
    public record BoardSpec(String boardId, String groupId, List<String> columnIds,
                            String statusColumn, String supStatusColumn, String openDateColumn) {
    }

    /** The two boards the reports read, delivery first because it is the smaller one. */
    public static final List<BoardSpec> BOARDS = List.of(
            new BoardSpec(DELIVERY_BOARD, DELIVERY_GROUP, DELIVERY_COLUMNS,
                    "status", "status_1", "date5"),
            new BoardSpec(CLEANING_BOARD, CLEANING_GROUP, CLEANING_COLUMNS,
                    "status", "status7", "date8"));

    /**
     * One board, by spec.
     *
     * <p>{@code @Transactional} belongs here and not only on the method below. This is the
     * method the coordinator calls, so this is where the proxy gets a chance to open a
     * transaction; the delegation below is a self-invocation and would start nothing on its
     * own. Annotating only the six-argument version was the first attempt and failed
     * identically — "No EntityManager with actual transaction available for current thread".
     */
    @Transactional
    public SyncResult sync(BoardSpec spec) {
        return sync(spec.boardId(), spec.groupId(), spec.columnIds(),
                spec.statusColumn(), spec.supStatusColumn(), spec.openDateColumn());
    }

    /**
     * One board, in one transaction.
     *
     * <p>Transactional as a whole deliberately. The absence pass closes every ticket the
     * board did not return, so a failure halfway through a partial read would mark live
     * cases closed. All of it lands or none of it does.
     */
    @Transactional
    public SyncResult sync(String boardId,
                           String groupId,
                           List<String> columnIds,
                           String statusColumn,
                           String supStatusColumn,
                           String openDateColumn) {

        List<MondayItem> items = boardReader.readGroupItems(boardId, groupId, columnIds);

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Counters counters = new Counters();
        Set<String> seenItemIds = new HashSet<>();

        for (MondayItem item : items) {
            seenItemIds.add(item.id());
            // Everything the group read returns is, by definition, in the open group.
            upsert(item, boardId, groupId, true, statusColumn, supStatusColumn, openDateColumn,
                    now, today, counters);
        }

        int closed = closeAbsent(boardId, seenItemIds, now);

        SyncResult result = counters.result(boardId, items.size(), closed);

        log.info("Synced board {}: {} seen, {} new, {} updated, {} closed, "
                        + "{} new comments, {} status changes",
                boardId, result.seen(), result.created(), result.updated(), closed,
                result.newComments(), result.statusChanges());

        return result;
    }

    /**
     * A filtered slice of one board - every group, only the rows matching the rules.
     *
     * <p>Written for the per-brand analytics, which need a robot brand's whole history
     * and not just its open cases. The rows land in the same table as the group sync
     * above, keyed on the same item id, so a ticket both syncs see is one row; what
     * differs is that <em>nothing is closed by absence here</em>. The filter returns a
     * subset of the board, so a row it did not return has not necessarily left the
     * board - it just did not match. {@code is_present} is instead set from the group
     * the row is in: true in the open group, false anywhere else.
     */
    @Transactional
    public SyncResult syncFiltered(String boardId,
                                   String openGroupId,
                                   List<MondayBoardReader.FilterRule> rules,
                                   List<String> columnIds,
                                   String statusColumn,
                                   String supStatusColumn,
                                   String openDateColumn) {

        List<MondayItem> items = boardReader.readFilteredItems(boardId, rules, columnIds);

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Counters counters = new Counters();

        for (MondayItem item : items) {
            String groupId = item.group() == null ? null : item.group().id();
            boolean open = openGroupId.equals(groupId);
            upsert(item, boardId, groupId, open, statusColumn, supStatusColumn, openDateColumn,
                    now, today, counters);
        }

        SyncResult result = counters.result(boardId, items.size(), 0);
        log.info("Synced filtered board {}: {} seen, {} new, {} updated, {} new comments, {} status changes",
                boardId, result.seen(), result.created(), result.updated(),
                result.newComments(), result.statusChanges());
        return result;
    }

    /** Running totals for one sync, so the two entry points share the loop body. */
    private static final class Counters {
        int created, updated, newComments, statusChanges;

        SyncResult result(String boardId, int seen, int closed) {
            return new SyncResult(boardId, seen, created, updated, closed, newComments, statusChanges);
        }
    }

    /** One row: create or update the ticket, then store its new comments and status move. */
    private void upsert(MondayItem item, String boardId, String groupId, boolean present,
                        String statusColumn, String supStatusColumn, String openDateColumn,
                        LocalDateTime now, LocalDate today, Counters counters) {

        CaseTicket ticket = tickets
                .findBySourceAndSourceItemId(CaseSource.MONDAY, item.id())
                .orElse(null);

        boolean isNew = ticket == null;
        if (isNew) {
            ticket = CaseTicket.builder()
                    .source(CaseSource.MONDAY)
                    .sourceBoardId(boardId)
                    .sourceItemId(item.id())
                    .build();
        }

        String status = item.columnText(statusColumn);
        String supStatus = item.columnText(supStatusColumn);

        ticket.setSourceGroupId(groupId);
        ticket.setSourceGroupTitle(item.groupTitle());
        ticket.setItemName(item.name());
        ticket.setStatus(status);
        ticket.setSupStatus(supStatus);
        ticket.setOpenDate(parseDate(item.columnText(openDateColumn)));
        ticket.setRawColumns(writeRawColumns(item));
        ticket.setSourceUpdatedAt(item.updatedAt() == null
                ? null
                : item.updatedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        ticket.setLastSyncedAt(now);

        // A ticket that left the group and came back is open again.
        ticket.setPresent(present);

        applyBoardSpecificFields(ticket, item, boardId);

        tickets.save(ticket);
        if (isNew) counters.created++; else counters.updated++;

        counters.newComments += storeNewComments(ticket, item);
        if (recordStatus(ticket, status, supStatus, today)) counters.statusChanges++;
    }

    /**
     * The fields whose column ids differ between the two boards.
     *
     * <p>Kept in one switch rather than spread through the loop: {@code text} is Solution
     * on delivery and Main Issue on cleaning, and {@code status_1} is Sup Status here and
     * Issue Level there. Reading either generically would fill the wrong column with
     * plausible-looking content, which is the failure that never gets noticed.
     */
    private void applyBoardSpecificFields(CaseTicket ticket, MondayItem item, String boardId) {
        if (DELIVERY_BOARD.equals(boardId)) {
            ticket.setProjectRaw(item.columnText("asset_owner"));
            ticket.setBranchRaw(item.columnText("text6"));
            ticket.setBranchCodeRaw(item.columnText("tags2"));
            ticket.setProvinceRaw(item.columnText("color_mm6mwh74"));
            ticket.setRobotModel(item.columnText("status_139"));
            ticket.setSerialNumbers(item.columnText("tags42"));
            ticket.setMainIssue(item.columnText("main_issue_key_word3"));
            ticket.setSolution(item.columnText("text"));
            ticket.setReActionDate(parseDate(item.columnText("date_18")));
        } else {
            ticket.setProjectRaw(item.columnText("asset_owner3__1"));
            ticket.setBranchRaw(item.columnText("text6"));
            // The cleaning board has no Province column; the SLA there is 3 days
            // everywhere, so nothing needs one.
            ticket.setRobotModel(item.columnText("status_17"));
            ticket.setSerialNumbers(item.columnText("text0"));
            ticket.setMainIssue(item.columnText("text"));
            ticket.setSolution(item.columnText("long_text"));
            ticket.setReActionDate(parseDate(item.columnText("date_1")));
        }
    }

    /**
     * Comments not stored yet.
     *
     * <p>Existing ids are read first rather than relying on the unique constraint to
     * reject duplicates: a comment is immutable once posted, so one already present needs
     * no work, and letting the insert fail would abort the whole board's sync over a row
     * carrying nothing new.
     */
    private int storeNewComments(CaseTicket ticket, MondayItem item) {
        if (item.updates() == null || item.updates().isEmpty()) return 0;

        Set<String> known = new HashSet<>(updates.findSourceUpdateIds(ticket.getId()));
        int stored = 0;

        for (MondayUpdate update : item.updates()) {
            if (storeComment(ticket, update, null, known)) stored++;
            // Replies arrive only on the filtered read; the group read leaves them null.
            if (update.replies() != null) {
                for (MondayUpdate reply : update.replies()) {
                    if (storeComment(ticket, reply, update.id(), known)) stored++;
                }
            }
        }
        return stored;
    }

    private boolean storeComment(CaseTicket ticket, MondayUpdate update, String parentUpdateId,
                                 Set<String> known) {
        if (update.id() == null || known.contains(update.id())) return false;

        updates.save(CaseTicketUpdate.builder()
                .caseTicketId(ticket.getId())
                .sourceUpdateId(update.id())
                .parentUpdateId(parentUpdateId)
                .body(update.textBody())
                // The record has a helper for this: it returns "unknown" rather than
                // null when monday omits the author, and the author matters here --
                // the *status* marker convention is one person's habit.
                .creatorName(update.creatorName())
                // UTC, pinned here rather than inherited from whatever offset the JSON
                // happened to be parsed with. See CaseTicketUpdate#postedAt.
                .postedAt(update.createdAt() == null
                        ? null
                        : update.createdAt().withOffsetSameInstant(ZoneOffset.UTC)
                                .toLocalDateTime())
                .build());
        known.add(update.id());
        return true;
    }

    /**
     * A status-history row, but only when the status actually moved.
     *
     * <p>Writing one per day regardless would bury the days a status changed under the
     * days it sat still, and the changes are what the history is kept for.
     *
     * <p>Today's row is updated rather than inserted twice: a second sync on the same day
     * must not trip {@code uq_case_ticket_status_day}, and a status flipped twice in an
     * afternoon should leave one line carrying the latest value.
     */
    private boolean recordStatus(CaseTicket ticket, String status, String supStatus,
                                 LocalDate today) {

        CaseTicketStatusHistory todays =
                history.findByCaseTicketIdAndObservedOn(ticket.getId(), today).orElse(null);

        if (todays != null) {
            if (same(todays.getStatus(), status) && same(todays.getSupStatus(), supStatus)) {
                return false;
            }
            todays.setStatus(status);
            todays.setSupStatus(supStatus);
            history.save(todays);
            return true;
        }

        CaseTicketStatusHistory previous = history
                .findFirstByCaseTicketIdOrderByObservedOnDescObservedAtDesc(ticket.getId())
                .orElse(null);

        if (previous != null
                && same(previous.getStatus(), status)
                && same(previous.getSupStatus(), supStatus)) {
            return false;
        }

        history.save(CaseTicketStatusHistory.builder()
                .caseTicketId(ticket.getId())
                .status(status)
                .supStatus(supStatus)
                .observedOn(today)
                .build());
        return true;
    }

    /**
     * Close the tickets the board did not return.
     *
     * <p>monday does not report that an item left a group, so absence is the only signal
     * a case has closed and it has to be derived by elimination.
     *
     * <p>⚠️ Which makes an empty read dangerous: it would close every open case on the
     * board. {@link MondayBoardReader} already turns an empty {@code boards} array into an
     * explicit "not visible to this token" error for exactly this reason, so a revoked
     * token throws rather than arriving here as zero items. A group that is genuinely
     * empty still closes everything, which is correct.
     */
    private int closeAbsent(String boardId, Set<String> seenItemIds, LocalDateTime now) {
        return seenItemIds.isEmpty()
                ? tickets.markAllAbsent(CaseSource.MONDAY, boardId, now)
                : tickets.markAbsent(CaseSource.MONDAY, boardId, seenItemIds, now);
    }

    private String writeRawColumns(MondayItem item) {
        try {
            Map<String, String> raw = new LinkedHashMap<>();
            if (item.columnValues() != null) {
                item.columnValues().forEach(v -> raw.put(v.id(), v.text()));
            }
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            // Losing the raw payload must not lose the sync: every mapped field is
            // already set by this point, and raw_columns is a convenience for later.
            log.warn("Could not serialise raw columns for item {}", item.id(), e);
            return null;
        }
    }

    private static boolean same(String a, String b) {
        return Objects.equals(
                a == null ? null : a.trim(),
                b == null ? null : b.trim());
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDate.parse(text.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
