package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.dto.SheetRow;
import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.dto.SheetTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final List<String> missingHeaders = new ArrayList<>();
    private final List<String> missingCriticalHeaders = new ArrayList<>();

    /** The mapped rows, and what stops some of them from being synced. */
    record MappedSheet(List<AotSheetCase> cases, int withoutId, List<String> duplicateIds) {

        /** The rows the sync can write: those with an id. */
        List<AotSheetCase> identified() {
            return cases.stream().filter(c -> c.rowId() != null).toList();
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
    }

    static AotSheetRowMapper forTable(SheetTable table, AotSheetProperties props) {
        return new AotSheetRowMapper(table, props);
    }

    /** Configured headers the sheet does not have. Blank configuration is not "missing". */
    List<String> missingHeaders() {
        return List.copyOf(missingHeaders);
    }

    /**
     * The missing headers the sync cannot run without: id, status, close date and open
     * date. A renamed descriptive column only blanks a field; a renamed id column would
     * re-create every case, and a renamed status column would reopen them all.
     */
    List<String> missingCriticalHeaders() {
        return List.copyOf(missingCriticalHeaders);
    }

    /** Whether a closure column was both configured and found. */
    boolean knowsClosure() {
        return status != null || closeDate != null;
    }

    AotSheetCase map(SheetRow row) {
        String statusText = row.text(status);
        var closedOn = row.date(closeDate);

        Boolean closed = null;
        if (knowsClosure()) {
            closed = closedOn != null
                    || (statusText != null && closedStatuses.contains(SheetTable.normalise(statusText)));
        }

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
                closed);
    }

    MappedSheet mapAll(SheetTable table) {
        List<AotSheetCase> cases = table.rows().stream().map(this::map).toList();

        int withoutId = 0;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AotSheetCase c : cases) {
            if (c.rowId() == null) {
                withoutId++;
            } else {
                counts.merge(c.rowId(), 1, Integer::sum);
            }
        }
        List<String> duplicates = counts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();

        return new MappedSheet(cases, withoutId, duplicates);
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
