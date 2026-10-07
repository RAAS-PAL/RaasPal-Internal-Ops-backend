package com.raaspal.robotrecommendation.casereport.aotsheet;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The linked sheet's open cases, as the last sync left them in {@code case_ticket}.
 *
 * <p>Only the sheet linked now: rows synced from a sheet that was since unlinked or
 * replaced stay in the table, but they are not AOT's cases any more and are not listed.
 *
 * @param linked       false when no sheet is linked; everything else is then empty
 * @param lastSyncedAt when the sheet was last read into the table; null if never
 */
public record AotSheetOpenCases(boolean linked,
                                String spreadsheetId,
                                LocalDateTime lastSyncedAt,
                                List<Item> cases) {

    static AotSheetOpenCases notLinked() {
        return new AotSheetOpenCases(false, null, null, List.of());
    }

    /**
     * One open case.
     *
     * @param rowId the sheet's own case id, without the spreadsheet prefix
     * @param days  whole days since the open date, not counting it, as the pending sheets count
     * @param waitingForPart fixed, and waiting for AOTGA to return the broken part: still
     *                       pending, but on hold, so not late
     */
    public record Item(String rowId,
                       String ticketNo,
                       LocalDate openDate,
                       Integer days,
                       String site,
                       String model,
                       String serialNumbers,
                       String problem,
                       String requestedPart,
                       String repairBy,
                       String verifyNote,
                       String status,
                       boolean waitingForPart) {
    }
}
