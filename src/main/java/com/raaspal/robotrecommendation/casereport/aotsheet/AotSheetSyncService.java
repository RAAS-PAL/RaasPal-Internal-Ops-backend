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

    private final GoogleSheetReader reader;
    private final GoogleSheetApiClient client;
    private final AotSheetSettingsService settings;
    private final CaseTicketRepository tickets;
    private final CaseTicketStatusHistoryRepository history;
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
        AotSheetRowMapper.MappedSheet mapped = mapper.mapAll(table);

        Integer open = null;
        Integer closed = null;
        if (mapper.knowsClosure()) {
            closed = (int) mapped.cases().stream().filter(c -> Boolean.TRUE.equals(c.closed())).count();
            open = mapped.cases().size() - closed;
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
                mapped.cases().stream().limit(Math.max(0, sampleSize)).toList());
    }

    /**
     * Writes the sheet into {@code case_ticket}, in one transaction.
     *
     * <p>Refuses rather than guesses whenever the result would be wrong in a way nobody
     * would notice: sync switched off, identity or closure not configured, one of those
     * columns renamed on the sheet, an empty read, or an id on two rows.
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

        AotSheetRowMapper.MappedSheet mapped = mapper.mapAll(table);
        if (!mapped.duplicateIds().isEmpty()) {
            throw new IllegalStateException("These ids are on more than one row of the AOT sheet: "
                    + mapped.duplicateIds().stream().limit(20).collect(Collectors.joining(", "))
                    + ". Each case needs its own id; nothing was synced.");
        }
        List<AotSheetCase> cases = mapped.identified();
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
        int closedByStatus = 0;
        Set<String> seen = new HashSet<>();

        for (AotSheetCase c : cases) {
            String itemId = itemId(spreadsheetId, c.rowId());
            seen.add(itemId);

            CaseTicket ticket = existing.get(itemId);
            boolean isNew = ticket == null;
            String previousStatus = null;
            if (isNew) {
                ticket = CaseTicket.builder()
                        .source(CaseSource.GOOGLE_SHEET)
                        .sourceBoardId(spreadsheetId)
                        .sourceItemId(itemId)
                        .build();
            } else {
                previousStatus = ticket.getStatus();
            }

            apply(ticket, c, rowsByNumber.get(c.sheetRow()), props.getTab(), now);
            tickets.save(ticket);
            if (isNew) created++; else updated++;
            if (Boolean.TRUE.equals(c.closed())) closedByStatus++;

            if (isNew || !same(previousStatus, c.status())) {
                if (recordStatus(ticket, c.status(), today)) statusChanges++;
            }
        }

        // Only rows deleted from the sheet: every row still on it was just written.
        int removed = tickets.markAbsent(CaseSource.GOOGLE_SHEET, spreadsheetId, seen, now);

        log.info("Synced AOT sheet {}: {} rows, {} new, {} updated, {} closed by status, "
                        + "{} removed from the sheet, {} skipped without id, {} status changes",
                spreadsheetId, cases.size(), created, updated, closedByStatus, removed,
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
                t.getStatus());
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
        ticket.setMainIssue(c.problem());
        // The RE's verification note is the sheet's nearest thing to a solution.
        ticket.setSolution(c.verifyNote());
        ticket.setOpenDate(c.openDate());
        ticket.setRawColumns(rawColumns(row));
        ticket.setLastSyncedAt(now);
        ticket.setPresent(!Boolean.TRUE.equals(c.closed()));
    }

    /** Every cell of the row, mapped or not, so a column needed later is already stored. */
    private String rawColumns(SheetRow row) {
        if (row == null) return null;
        try {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("_sheetRow", row.rowNumber());
            raw.putAll(row.cells());
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            log.warn("Could not serialise AOT sheet row {}", row.rowNumber(), e);
            return null;
        }
    }

    /**
     * A status-history row when the status moved - the same rule as the monday sync,
     * which keeps its version private. The sheet has no Sup Status, so that stays null.
     *
     * <p>Only called for new rows and rows whose status changed since the last sync, so
     * an unchanged sheet costs no history queries at all.
     */
    private boolean recordStatus(CaseTicket ticket, String status, LocalDate today) {
        CaseTicketStatusHistory todays =
                history.findByCaseTicketIdAndObservedOn(ticket.getId(), today).orElse(null);

        if (todays != null) {
            if (same(todays.getStatus(), status)) return false;
            todays.setStatus(status);
            history.save(todays);
            return true;
        }

        CaseTicketStatusHistory previous = history
                .findFirstByCaseTicketIdOrderByObservedOnDescObservedAtDesc(ticket.getId())
                .orElse(null);
        if (previous != null && same(previous.getStatus(), status)) return false;

        history.save(CaseTicketStatusHistory.builder()
                .caseTicketId(ticket.getId())
                .status(status)
                .observedOn(today)
                .build());
        return true;
    }

    private static boolean same(String a, String b) {
        return Objects.equals(a == null ? null : a.trim(), b == null ? null : b.trim());
    }
}
