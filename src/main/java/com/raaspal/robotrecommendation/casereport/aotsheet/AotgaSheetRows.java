package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.SheetCells;
import com.raaspal.robotrecommendation.casereport.entity.CaseSource;
import com.raaspal.robotrecommendation.casereport.entity.CaseTicket;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * AOT's sheet as the AOTGA tracker reads it: every synced row of the linked sheet, still
 * on it or not, from {@code case_ticket}.
 *
 * <p>The mirror rather than a live read of Google: the sync has already decided each row's
 * colour and whether it is still open, and the tracker must agree with what the rest of
 * the tab says. It is brought up to date first when the last sync is more than a minute
 * old, so the tracker is never a full refresh cycle behind the sheet.
 */
@Component
@RequiredArgsConstructor
public class AotgaSheetRows {

    /** How stale the mirror may be before the tracker syncs the sheet first. */
    private static final Duration MAX_AGE = Duration.ofMinutes(1);

    /**
     * One row of AOT's sheet.
     *
     * @param key           the row's key in {@code case_ticket}, stable across syncs
     * @param ticketNo      AOT's ticket number - the id column
     * @param open          still white on the sheet; false once blue, or gone from it
     * @param colour        the row's colour at the last sync, {@code #rrggbb}; tells a blue
     *                      row from one deleted from the sheet, which is also not open
     * @param requestedPart the "Request for Spare part" cell; filled once a part is asked for
     * @param partSentOn    the "Spare Part Received" date: AOT has the spare part we sent
     * @param sheetRow      the row's number on the sheet at the last sync, for finding it
     */
    public record Ticket(String key,
                         String ticketNo,
                         String site,
                         String robot,
                         String serial,
                         String problem,
                         LocalDate openDate,
                         String status,
                         boolean open,
                         String colour,
                         String requestedPart,
                         LocalDate partSentOn,
                         Integer sheetRow) {
    }

    private final AotSheetSettingsService settings;
    private final AotSheetAutoSync autoSync;
    private final CaseTicketRepository tickets;
    private final ObjectMapper objectMapper;

    public List<Ticket> read() {
        autoSync.syncFirstIfDue(MAX_AGE);
        AotSheetProperties props = settings.effective();
        if (!props.isConfigured()) return List.of();
        return tickets.findBySourceAndSourceBoardId(CaseSource.GOOGLE_SHEET, props.getSpreadsheetId()).stream()
                .map(t -> ticket(t, raw(t.getRawColumns()), props))
                .toList();
    }

    static Ticket ticket(CaseTicket t, Map<String, Object> raw, AotSheetProperties props) {
        String key = t.getSourceItemId();
        String rowId = key == null ? null : key.substring(key.indexOf(':') + 1);
        return new Ticket(
                key,
                rowId,
                t.getBranchRaw(),
                t.getRobotModel(),
                t.getSerialNumbers(),
                t.getMainIssue(),
                t.getOpenDate(),
                t.getStatus(),
                t.isPresent(),
                SheetCells.text(raw.get(AotSheetSyncService.RAW_COLOUR)),
                SheetCells.text(raw.get(props.getRequestedPartHeader())),
                SheetCells.date(raw.get(props.getPartReceivedHeader())),
                raw.get("_sheetRow") instanceof Number n ? n.intValue() : null);
    }

    private Map<String, Object> raw(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
