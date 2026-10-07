package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.GoogleSheetApiClient;
import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.GoogleSheetReader;
import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.dto.SheetRow;
import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.dto.SheetTable;
import com.raaspal.robotrecommendation.casereport.entity.CaseSource;
import com.raaspal.robotrecommendation.casereport.entity.CaseTicket;
import com.raaspal.robotrecommendation.casereport.entity.CaseTicketStatusHistory;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketRepository;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketStatusHistoryRepository;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mirrors the AOT team's Google Sheet into {@code case_ticket}, beside the monday boards.
 *
 * <p>AOT runs its own technicians and logs its cases in its own sheet, not on monday, so
 * the AOT report needs this second source. Each sheet row becomes one {@code case_ticket}
 * row with {@code source = GOOGLE_SHEET} and {@code source_board_id} = the spreadsheet's
 * id. Every existing reader of the table filters by a monday board id, so these rows
 * reach no current report until a report asks for them.
 *
 * <p><strong>Closure works differently from monday.</strong> A monday case closes by
 * leaving the All Case group, so the monday sync closes whatever it did not see. A sheet
 * row never leaves - the log runs back to 2023 - so a case here is closed only when its
 * status or close-date column says so. Absence still closes a row, but only for a row
 * deleted from the sheet.
 *
 * <p>Nothing is merged with monday at sync time. An AOT case logged on both keeps both
 * rows; pairing them is a report-time decision that can be changed without a re-sync.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AotSheetSyncService {

    /** Bangkok, for the reason {@code CaseTicketSyncService} gives: the snapshot's day. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    /**
     * The sup status of a row whose status says closed but whose colour does not: fixed,
     * and waiting for AOTGA to return the broken part. Contains "On hold" on purpose -
     * that is what the SLA rules look for to stop a case's clock.
     */
    public static final String WAITING_FOR_PART = "On hold: waiting for AOTGA to return the part";

    /** The key the sync adds to a row's raw cells for its colour. */
    static final String RAW_COLOUR = "_colour";

    /** How many row numbers the preview lists for rows without an id or with a repeated one. */
    private static final int PREVIEW_ROW_LIST = 50;

    private final GoogleSheetReader reader;
    private final GoogleSheetApiClient client;
    private final AotSheetSettingsService settings;
    private final CaseTicketRepository tickets;
    private final CaseTicketStatusHistoryRepository history;
    private final AotgaClaimRepository claims;

    /**
     * The rows of the last sync that have no case id: not stored - without an id a row cannot
     * be told from the next - but kept here so the tracker can list them for fixing. Empty
     * until this process has synced once.
     */
    private volatile List<AotSheetCase> unidentified = List.of();
    /**
     * The rows of the last sync whose id is on another row too, all of them closed (an open
     * one stops the sync): not stored, since one id cannot be two cases, but kept for the
     * tracker to list apart. Empty until this process has synced once.
     */
    private volatile List<AotSheetCase> repeated = List.of();
    /** Whether this process has synced, so {@link #unidentified} and {@link #repeated} are filled. */
    private volatile boolean syncedSinceStart;
    private final ObjectMapper objectMapper;

    public boolean isEnabled() {
        return settings.effective().isSyncEnabled();
    }

    /** When the linked sheet was last synced, from the table; null when none is linked or it never was. */
    @Transactional(readOnly = true)
    public LocalDateTime lastSyncedAt() {
        AotSheetProperties props = settings.effective();
        return props.isConfigured() ? tickets.lastSyncedAt(CaseSource.GOOGLE_SHEET, props.getSpreadsheetId()) : null;
    }

    /**
     * Reads the sheet and reports what the sync would do with it. Writes nothing, so it
     * is safe to call from a dev machine pointed at the production database.
     *
     * @param sampleSize how many mapped rows to include
     */
    public AotSheetPreview preview(int sampleSize) {
        AotSheetProperties props = settings.effective();
        if (!props.isConfigured()) {
            throw new IllegalStateException(
                    "No AOT sheet configured: paste its link on the AOT tab of Pending cases and save.");
        }

        SheetTable table = readTable(props);
        AotSheetRowMapper mapper = AotSheetRowMapper.forTable(table, props);
        AotSheetPreview.Suggested suggested = suggest(table.headers());
        // Read whenever there is a column to read, closed colours chosen or not: the list
        // of colours the sheet uses is what they are chosen from. Before a status column is
        // saved, the one it most likely is, so the first setup can list them too.
        String colourColumn = mapper.colourColumn() != null ? mapper.colourColumn() : suggested.status();
        Map<Integer, String> colours = colourColumn == null ? Map.of() : readColours(props, table, colourColumn);
        AotSheetRowMapper.MappedSheet mapped = mapper.mapAll(table, colours);

        Integer open = null;
        Integer waiting = null;
        Integer closed = null;
        if (mapper.knowsClosure()) {
            closed = count(mapped, AotSheetCase.State.CLOSED);
            waiting = mapper.readsColour() ? count(mapped, AotSheetCase.State.WAITING) : null;
            open = count(mapped, AotSheetCase.State.OPEN);
        }

        int unreadableDates = (int) table.rows().stream().filter(mapper::openDateUnreadable).count();

        return new AotSheetPreview(
                props.getSpreadsheetId(),
                props.getTab(),
                client.serviceAccountEmail(),
                props.isSyncEnabled(),
                table.headers(),
                mapper.missingHeaders(),
                props.notReadyForSync(),
                mapped.cases().size(),
                mapped.withoutId(),
                mapped.duplicateIds(),
                unreadableDates,
                open,
                closed,
                mapped.cases().stream().limit(Math.max(0, sampleSize)).toList(),
                mapped.rowsWithoutId().stream().limit(PREVIEW_ROW_LIST).toList(),
                duplicates(mapped),
                colourColumn,
                colourCounts(mapped, mapper),
                waiting,
                suggested);
    }

    /**
     * The column each field most likely is, by its header: the first one that matches, left
     * to right. Only a starting point - the setup shows it in a dropdown to confirm.
     */
    static AotSheetPreview.Suggested suggest(List<String> headers) {
        return new AotSheetPreview.Suggested(
                first(headers, "ticket", "case id", "case no"),
                first(headers, "issue date", "open date", "date"),
                first(headers, "status"));
    }

    private static String first(List<String> headers, String... words) {
        for (String word : words) {
            for (String header : headers) {
                if (SheetTable.normalise(header).contains(word)) return header;
            }
        }
        return null;
    }

    /** Repeated ids, those that stop a sync first. */
    private static List<AotSheetPreview.DuplicateId> duplicates(AotSheetRowMapper.MappedSheet mapped) {
        Set<String> blocking = mapped.blockingDuplicateIds();
        return mapped.duplicateRows().entrySet().stream()
                .map(e -> new AotSheetPreview.DuplicateId(e.getKey(), e.getValue(), blocking.contains(e.getKey())))
                .sorted(Comparator.comparing(AotSheetPreview.DuplicateId::blocksSync).reversed())
                .limit(PREVIEW_ROW_LIST)
                .toList();
    }

    private static int count(AotSheetRowMapper.MappedSheet mapped, AotSheetCase.State state) {
        return (int) mapped.cases().stream().filter(c -> c.state() == state).count();
    }

    /** Each colour the colour column uses, on how many rows, most used first. */
    private static List<AotSheetPreview.ColourCount> colourCounts(AotSheetRowMapper.MappedSheet mapped,
                                                                  AotSheetRowMapper mapper) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        mapped.cases().stream()
                .map(AotSheetCase::colour)
                .filter(Objects::nonNull)
                .forEach(colour -> counts.merge(colour, 1, Integer::sum));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> new AotSheetPreview.ColourCount(e.getKey(), e.getValue(), mapper.isClosedColour(e.getKey())))
                .toList();
    }

    private Map<Integer, String> readColours(AotSheetProperties props, SheetTable table, String column) {
        return reader.readColours(props.getSpreadsheetId(), props.getTab(), props.getHeaderRow(), table, column);
    }

    /**
     * Writes the sheet into {@code case_ticket}, in one transaction.
     *
     * <p>Refuses rather than guesses whenever the result would be wrong in a way nobody
     * would notice: sync switched off, identity or closure not configured, one of those
     * columns renamed on the sheet, an empty read, or an id on two rows of which one is
     * still open. An id repeated on closed rows only is skipped instead: the log goes back
     * to 2023, and merging two closed cases changes nothing on the pending list.
     */
    @Transactional
    public SyncResult sync() {
        AotSheetProperties props = settings.effective();
        if (!props.isSyncEnabled()) {
            throw new IllegalStateException("The AOT sheet sync is off. Check "
                    + "the sheet on the AOT tab of Pending cases (Test connection), then turn the sync on there.");
        }
        List<String> notReady = props.notReadyForSync();
        if (!notReady.isEmpty()) {
            throw new IllegalStateException("The AOT sheet sync is not configured: "
                    + String.join("; ", notReady));
        }

        SheetTable table = readTable(props);
        if (table.rows().isEmpty()) {
            // An empty read is far likelier a wrong tab or header row than an empty log,
            // and treating it as real would close every AOT case on record.
            throw new IllegalStateException("The AOT sheet returned no rows from tab '"
                    + props.getTab() + "'; refusing to sync an empty read.");
        }

        AotSheetRowMapper mapper = AotSheetRowMapper.forTable(table, props);
        if (!mapper.missingCriticalHeaders().isEmpty()) {
            throw new IllegalStateException("The AOT sheet has no column named "
                    + String.join(", ", mapper.missingCriticalHeaders())
                    + ". Renamed? Update the mapping; nothing was synced.");
        }
        if (!mapper.missingHeaders().isEmpty()) {
            log.warn("AOT sheet: columns not found, their fields stay empty: {}", mapper.missingHeaders());
        }

        Map<Integer, String> colours = mapper.readsColour() ? readColours(props, table, mapper.colourColumn()) : Map.of();
        AotSheetRowMapper.MappedSheet mapped = mapper.mapAll(table, colours);
        Set<String> blocking = mapped.blockingDuplicateIds();
        if (!blocking.isEmpty()) {
            throw new IllegalStateException("These ids are on more than one row of the AOT sheet, "
                    + "and at least one of those rows is still open: "
                    + blocking.stream()
                            .limit(20)
                            .map(id -> id + " (rows " + mapped.duplicateRows().get(id).stream()
                                    .map(String::valueOf).collect(Collectors.joining(", ")) + ")")
                            .collect(Collectors.joining("; "))
                    + ". Each case needs its own id; nothing was synced.");
        }
        if (!mapped.duplicateIds().isEmpty()) {
            log.warn("AOT sheet: {} id(s) repeated on closed rows only; those rows were skipped and are listed apart",
                    mapped.duplicateIds().size());
        }
        List<AotSheetCase> cases = mapped.syncable();
        if (cases.isEmpty()) {
            throw new IllegalStateException("No row of the AOT sheet has a value in '"
                    + props.getRowIdHeader() + "'; nothing was synced.");
        }
        if (mapped.withoutId() > 0) {
            log.warn("AOT sheet: {} row(s) have no id and were skipped", mapped.withoutId());
        }

        String spreadsheetId = props.getSpreadsheetId();
        Map<String, CaseTicket> existing = tickets
                .findBySourceAndSourceBoardId(CaseSource.GOOGLE_SHEET, spreadsheetId).stream()
                .collect(Collectors.toMap(CaseTicket::getSourceItemId, Function.identity()));
        Map<Integer, SheetRow> rowsByNumber = table.rows().stream()
                .collect(Collectors.toMap(SheetRow::rowNumber, Function.identity()));

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        int created = 0;
        int updated = 0;
        int statusChanges = 0;
        int closedCount = 0;
        int waitingCount = 0;
        Set<String> seen = new HashSet<>();

        for (AotSheetCase c : cases) {
            String itemId = itemId(spreadsheetId, c.rowId());
            seen.add(itemId);

            CaseTicket ticket = existing.get(itemId);
            boolean isNew = ticket == null;
            boolean wasOpen = !isNew && ticket.isPresent();
            String previousStatus = null;
            String previousSupStatus = null;
            if (isNew) {
                ticket = CaseTicket.builder()
                        .source(CaseSource.GOOGLE_SHEET)
                        .sourceBoardId(spreadsheetId)
                        .sourceItemId(itemId)
                        .build();
            } else {
                previousStatus = ticket.getStatus();
                previousSupStatus = ticket.getSupStatus();
            }

            apply(ticket, c, rowsByNumber.get(c.sheetRow()), props.getTab(), now);
            tickets.save(ticket);
            if (isNew) created++; else updated++;
            if (c.state() == AotSheetCase.State.CLOSED) {
                closedCount++;
                // Blue now, white last time: AOT has just sent the old part back, and
                // claiming it from the manufacturer is RAASPAL's step to record.
                if (wasOpen) markOldPartBack(spreadsheetId, c.rowId(), today, OffsetDateTime.now());
            }
            if (c.state() == AotSheetCase.State.WAITING) waitingCount++;

            // The sup status carries "waiting for its part", so a row turning blue is a
            // change worth a history row even though its status text stays the same.
            if (isNew || !same(previousStatus, c.status()) || !same(previousSupStatus, ticket.getSupStatus())) {
                if (recordStatus(ticket, c.status(), ticket.getSupStatus(), today)) statusChanges++;
            }
        }

        // Only rows deleted from the sheet: every row still on it was just written.
        int removed = tickets.markAbsent(CaseSource.GOOGLE_SHEET, spreadsheetId, seen, now);
        unidentified = mapped.cases().stream().filter(c -> c.rowId() == null).toList();
        repeated = mapped.identified().stream().filter(c -> mapped.duplicateRows().containsKey(c.rowId())).toList();
        syncedSinceStart = true;

        log.info("Synced AOT sheet {}: {} rows, {} new, {} updated, {} closed, {} waiting for a part, "
                        + "{} removed from the sheet, {} skipped without id, {} status changes",
                spreadsheetId, cases.size(), created, updated, closedCount, waitingCount, removed,
                mapped.withoutId(), statusChanges);

        return new SyncResult(spreadsheetId, cases.size(), created, updated, removed, 0, statusChanges);
    }

    /**
     * The linked sheet's open cases, oldest first, from {@code case_ticket}: no call to
     * Google, so it is as fresh as the last sync and costs one query.
     */
    @Transactional(readOnly = true)
    public AotSheetOpenCases openCases() {
        AotSheetProperties props = settings.effective();
        if (!props.isConfigured()) {
            return AotSheetOpenCases.notLinked();
        }
        String spreadsheetId = props.getSpreadsheetId();
        List<CaseTicket> rows = tickets.findBySourceAndSourceBoardId(CaseSource.GOOGLE_SHEET, spreadsheetId);
        LocalDateTime lastSynced = rows.stream()
                .map(CaseTicket::getLastSyncedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        List<AotSheetOpenCases.Item> open = rows.stream()
                .filter(CaseTicket::isPresent)
                .map(t -> openCase(t, readRaw(t.getRawColumns()), props, today))
                .sorted(Comparator.comparing(AotSheetOpenCases.Item::openDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return new AotSheetOpenCases(true, spreadsheetId, lastSynced, open);
    }

    /** One stored row as the AOT tab lists it; part and repairer come from the row's raw cells. */
    static AotSheetOpenCases.Item openCase(CaseTicket t, Map<String, Object> raw,
                                           AotSheetProperties props, LocalDate today) {
        String id = t.getSourceItemId();
        String rowId = id == null ? null : id.substring(id.indexOf(':') + 1);
        Integer days = t.getOpenDate() == null || t.getOpenDate().isAfter(today)
                ? null
                : (int) ChronoUnit.DAYS.between(t.getOpenDate(), today);
        return new AotSheetOpenCases.Item(
                rowId,
                t.getItemName(),
                t.getOpenDate(),
                days,
                t.getBranchRaw(),
                t.getRobotModel(),
                t.getSerialNumbers(),
                t.getMainIssue(),
                cell(raw, props.getRequestedPartHeader()),
                cell(raw, props.getRepairByHeader()),
                t.getSolution(),
                t.getStatus(),
                WAITING_FOR_PART.equals(t.getSupStatus()));
    }

    private Map<String, Object> readRaw(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String cell(Map<String, Object> raw, String header) {
        if (header == null || header.isBlank()) return null;
        Object value = raw.get(header);
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * The row's key in {@code case_ticket}. Prefixed with the spreadsheet id because the
     * table's unique key is {@code (source, source_item_id)} with no board in it: a second
     * sheet numbering its rows from 1 would otherwise collide with this one.
     */
    static String itemId(String spreadsheetId, String rowId) {
        return spreadsheetId + ":" + rowId;
    }

    private SheetTable readTable(AotSheetProperties props) {
        return reader.readTable(props.getSpreadsheetId(), props.getTab(), props.getHeaderRow());
    }

    private void apply(CaseTicket ticket, AotSheetCase c, SheetRow row, String tab, LocalDateTime now) {
        ticket.setSourceGroupId(tab);
        ticket.setSourceGroupTitle(tab);
        ticket.setItemName(c.ticketNo() != null ? c.ticketNo() : "AOT sheet " + c.rowId());
        ticket.setSerialNumbers(c.serialNumbers());
        // The whole sheet is AOT's, so it has no project column; Location is the airport.
        ticket.setBranchRaw(c.site());
        ticket.setRobotModel(c.model());
        ticket.setStatus(c.status());
        // The sheet has no Sup Status of its own; this is where a hold goes, worded so that
        // the SLA rules' "on hold" match stops the clock.
        ticket.setSupStatus(supStatus(c));
        ticket.setMainIssue(c.problem());
        // The RE's verification note is the sheet's nearest thing to a solution.
        ticket.setSolution(c.verifyNote());
        ticket.setOpenDate(c.openDate());
        ticket.setRawColumns(rawColumns(row, c));
        ticket.setLastSyncedAt(now);
        ticket.setPresent(!Boolean.TRUE.equals(c.closed()));
    }

    /** The last sync's rows without a case id, for listing apart; see {@link #unidentified}. */
    public List<AotSheetCase> unidentifiedRows() {
        return unidentified;
    }

    /** Whether this process has synced since it started; until then the lists apart are empty. */
    public boolean syncedSinceStart() {
        return syncedSinceStart;
    }

    /** The last sync's rows sharing an id with another row, for listing apart; see {@link #repeated}. */
    public List<AotSheetCase> repeatedRows() {
        return repeated;
    }

    /** Starts the claim step for a ticket, unless it already has one. */
    private void markOldPartBack(String spreadsheetId, String ticketNo, LocalDate today, OffsetDateTime now) {
        if (claims.findBySpreadsheetIdAndTicketNo(spreadsheetId, ticketNo).isPresent()) return;
        claims.save(AotgaClaim.builder()
                .spreadsheetId(spreadsheetId)
                .ticketNo(ticketNo)
                .oldPartBackOn(today)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /** A held row's Sup Status: waiting for its part. */
    static String supStatus(AotSheetCase c) {
        return c.state() == AotSheetCase.State.WAITING ? WAITING_FOR_PART : null;
    }

    /**
     * Every cell of the row, mapped or not, so a column needed later is already stored;
     * and the colour, when it was read.
     */
    private String rawColumns(SheetRow row, AotSheetCase c) {
        if (row == null) return null;
        try {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("_sheetRow", row.rowNumber());
            if (c.colour() != null) raw.put(RAW_COLOUR, c.colour());
            raw.putAll(row.cells());
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            log.warn("Could not serialise AOT sheet row {}", row.rowNumber(), e);
            return null;
        }
    }

    /**
     * A status-history row when the status or the sup status moved - the same rule as the
     * monday sync, which keeps its version private. The sup status is the sheet's
     * "waiting for its part", so the day a waiting row turns closed is on record too.
     *
     * <p>Only called for new rows and rows whose status changed since the last sync, so
     * an unchanged sheet costs no history queries at all.
     */
    private boolean recordStatus(CaseTicket ticket, String status, String supStatus, LocalDate today) {
        CaseTicketStatusHistory todays =
                history.findByCaseTicketIdAndObservedOn(ticket.getId(), today).orElse(null);

        if (todays != null) {
            if (same(todays.getStatus(), status) && same(todays.getSupStatus(), supStatus)) return false;
            todays.setStatus(status);
            todays.setSupStatus(supStatus);
            history.save(todays);
            return true;
        }

        CaseTicketStatusHistory previous = history
                .findFirstByCaseTicketIdOrderByObservedOnDescObservedAtDesc(ticket.getId())
                .orElse(null);
        if (previous != null && same(previous.getStatus(), status) && same(previous.getSupStatus(), supStatus)) {
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

    private static boolean same(String a, String b) {
        return Objects.equals(a == null ? null : a.trim(), b == null ? null : b.trim());
    }
}
