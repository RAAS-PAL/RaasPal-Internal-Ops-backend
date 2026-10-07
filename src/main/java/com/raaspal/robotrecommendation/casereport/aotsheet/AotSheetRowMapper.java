package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.dto.SheetRow;
import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.dto.SheetTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads AOT sheet rows into {@link AotSheetCase}s, with the configured header names
 * resolved against the sheet's actual headers once, up front.
 *
 * <p>No I/O here, so the preview and the sync see exactly the same reading of each row,
 * and it can be tested without a sheet or a database.
 */
final class AotSheetRowMapper {

    private final String rowId;
    private final String status;
    private final String closeDate;
    private final String openDate;
    private final String ticketNo;
    private final String serial;
    private final String model;
    private final String site;
    private final String problem;
    private final String requestedPart;
    private final String verify;
    private final String repairBy;

    private final Set<String> closedStatuses;
    /** The column whose colour is read; null when there is none to read. */
    private final String colourColumn;
    private final Set<String> closedColours;
    private final boolean readsColour;
    private final List<String> missingHeaders = new ArrayList<>();
    private final List<String> missingCriticalHeaders = new ArrayList<>();

    /**
     * The mapped rows, and what stops some of them from being synced.
     *
     * @param rowsWithoutId the sheet's row numbers whose id cell is empty
     * @param duplicateRows each id on more than one row, with the rows it is on
     */
    record MappedSheet(List<AotSheetCase> cases,
                       List<Integer> rowsWithoutId,
                       Map<String, List<Integer>> duplicateRows) {

        int withoutId() {
            return rowsWithoutId.size();
        }

        List<String> duplicateIds() {
            return List.copyOf(duplicateRows.keySet());
        }

        /** The rows with an id, repeated or not. */
        List<AotSheetCase> identified() {
            return cases.stream().filter(c -> c.rowId() != null).toList();
        }

        /**
         * The repeated ids that must stop a sync: those on at least one row that is not
         * closed. Two rows sharing an id become one case, so a pending row could be hidden
         * behind a closed one or a closed one reopened. When every row of an id is closed,
         * merging them changes nothing anybody sees, so those are only skipped.
         */
        Set<String> blockingDuplicateIds() {
            return cases.stream()
                    .filter(c -> c.rowId() != null && duplicateRows.containsKey(c.rowId()))
                    .filter(c -> c.state() != AotSheetCase.State.CLOSED)
                    .map(AotSheetCase::rowId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        /** The rows the sync writes: an id of their own. Rows sharing an id are left out. */
        List<AotSheetCase> syncable() {
            return identified().stream().filter(c -> !duplicateRows.containsKey(c.rowId())).toList();
        }
    }

    private AotSheetRowMapper(SheetTable table, AotSheetProperties props) {
        rowId = resolve(table, props.getRowIdHeader(), true);
        status = resolve(table, props.getStatusHeader(), true);
        closeDate = resolve(table, props.getCloseDateHeader(), true);
        openDate = resolve(table, props.getOpenDateHeader(), true);
        ticketNo = resolve(table, props.getTicketNoHeader(), false);
        serial = resolve(table, props.getSerialHeader(), false);
        model = resolve(table, props.getModelHeader(), false);
        site = resolve(table, props.getSiteHeader(), false);
        problem = resolve(table, props.getProblemHeader(), false);
        requestedPart = resolve(table, props.getRequestedPartHeader(), false);
        verify = resolve(table, props.getVerifyHeader(), false);
        repairBy = resolve(table, props.getRepairByHeader(), false);

        closedStatuses = new LinkedHashSet<>();
        props.getClosedStatuses().stream()
                .filter(s -> !AotSheetProperties.isBlank(s))
                .map(SheetTable::normalise)
                .forEach(closedStatuses::add);

        closedColours = new LinkedHashSet<>();
        props.getClosedColours().stream()
                .map(AotSheetRowMapper::normaliseColour)
                .filter(Objects::nonNull)
                .forEach(closedColours::add);
        // Blank means the status column, already resolved above. A column of its own is as
        // critical as the status once colours decide closure: renamed, it would reopen
        // every closed case.
        colourColumn = AotSheetProperties.isBlank(props.getColourHeader())
                ? status
                : resolve(table, props.getColourHeader(), !closedColours.isEmpty());
        readsColour = colourColumn != null && !closedColours.isEmpty();
    }

    static AotSheetRowMapper forTable(SheetTable table, AotSheetProperties props) {
        return new AotSheetRowMapper(table, props);
    }

    /** {@code #rrggbb} in lower case, from {@code #RRGGBB} or {@code rrggbb}; null for anything else. */
    static String normaliseColour(String value) {
        if (value == null) return null;
        String hex = value.trim().toLowerCase(Locale.ROOT);
        if (hex.startsWith("#")) hex = hex.substring(1);
        return hex.matches("[0-9a-f]{6}") ? "#" + hex : null;
    }

    /** Configured headers the sheet does not have. Blank configuration is not "missing". */
    List<String> missingHeaders() {
        return List.copyOf(missingHeaders);
    }

    /**
     * The missing headers the sync cannot run without: id, status, close date, open date,
     * and the colour column once colours decide closure. A renamed descriptive column only
     * blanks a field; a renamed id column would re-create every case, and a renamed status
     * column would reopen them all.
     */
    List<String> missingCriticalHeaders() {
        return List.copyOf(missingCriticalHeaders);
    }

    /** Whether a closure column was both configured and found. */
    boolean knowsClosure() {
        return status != null || closeDate != null || readsColour;
    }

    /** The column whose colour is read, as the sheet spells it; null when there is none. */
    String colourColumn() {
        return colourColumn;
    }

    /** Whether closure is judged by colour: closed colours chosen and their column found. */
    boolean readsColour() {
        return readsColour;
    }

    /** Whether {@code colour} is one of those chosen to mean closed. */
    boolean isClosedColour(String colour) {
        return colour != null && closedColours.contains(colour);
    }

    AotSheetCase map(SheetRow row) {
        return map(row, null);
    }

    /**
     * @param colour the row's colour in the colour column; null when colours were not read
     */
    AotSheetCase map(SheetRow row, String colour) {
        String statusText = row.text(status);
        var closedOn = row.date(closeDate);
        boolean closedByStatus = statusText != null && closedStatuses.contains(SheetTable.normalise(statusText));

        AotSheetCase.State state = null;
        if (readsColour) {
            // The colour decides closed. A closed status on a row that is not that colour
            // is fixed and waiting for its part: still pending.
            state = isClosedColour(colour) || closedOn != null ? AotSheetCase.State.CLOSED
                    : closedByStatus ? AotSheetCase.State.WAITING
                    : AotSheetCase.State.OPEN;
        } else if (knowsClosure()) {
            state = closedOn != null || closedByStatus ? AotSheetCase.State.CLOSED : AotSheetCase.State.OPEN;
        }
        Boolean closed = state == null ? null : state == AotSheetCase.State.CLOSED;

        return new AotSheetCase(
                row.rowNumber(),
                row.text(rowId),
                row.text(ticketNo),
                row.date(openDate),
                row.text(serial),
                row.text(model),
                row.text(site),
                row.text(problem),
                row.text(requestedPart),
                row.text(verify),
                row.text(repairBy),
                statusText,
                closedOn,
                closed,
                colour,
                state);
    }

    MappedSheet mapAll(SheetTable table) {
        return mapAll(table, Map.of());
    }

    /**
     * @param colours each row's colour by row number, as the colour column holds it; empty
     *                when colours were not read
     */
    MappedSheet mapAll(SheetTable table, Map<Integer, String> colours) {
        List<AotSheetCase> cases = table.rows().stream()
                .map(row -> map(row, colours.get(row.rowNumber())))
                .toList();

        List<Integer> withoutId = new ArrayList<>();
        Map<String, List<Integer>> rowsById = new LinkedHashMap<>();
        for (AotSheetCase c : cases) {
            if (c.rowId() == null) {
                withoutId.add(c.sheetRow());
            } else {
                rowsById.computeIfAbsent(c.rowId(), id -> new ArrayList<>()).add(c.sheetRow());
            }
        }
        Map<String, List<Integer>> duplicates = new LinkedHashMap<>();
        rowsById.forEach((id, rows) -> {
            if (rows.size() > 1) duplicates.put(id, List.copyOf(rows));
        });

        return new MappedSheet(cases, List.copyOf(withoutId), duplicates);
    }

    /** True when the open-date cell holds something that did not read as a date. */
    boolean openDateUnreadable(SheetRow row) {
        return openDate != null && row.get(openDate) != null && row.date(openDate) == null;
    }

    private String resolve(SheetTable table, String configured, boolean critical) {
        if (AotSheetProperties.isBlank(configured)) return null;
        String found = table.header(configured).orElse(null);
        if (found == null) {
            missingHeaders.add(configured);
            if (critical) missingCriticalHeaders.add(configured);
        }
        return found;
    }
}
