package com.raaspal.robotrecommendation.casereport.aotsheet;

import java.time.LocalDate;

/**
 * One row of the AOT sheet, read into named fields.
 *
 * @param sheetRow the row number the sheet shows, for finding it by eye
 * @param rowId    the configured id column's value; null when that column is not
 *                 configured or the cell is empty
 * @param closed   null when no closure column is configured - unknown, which is not
 *                 the same as open
 * @param colour   the row's background colour, {@code #rrggbb}; null when no colour is read
 * @param state    open, waiting for its part, or closed; null when closure is unknown
 */
public record AotSheetCase(int sheetRow,
                           String rowId,
                           String ticketNo,
                           LocalDate openDate,
                           String serialNumbers,
                           String model,
                           String site,
                           String problem,
                           String requestedPart,
                           String verifyNote,
                           String repairBy,
                           String status,
                           LocalDate closeDate,
                           Boolean closed,
                           String colour,
                           State state) {

    /**
     * Where a case stands. {@link #WAITING} only exists when closure is read by colour: the
     * status says closed but the row is not highlighted, because the robot is fixed and
     * AOTGA has not returned the broken part yet. It stays pending.
     */
    public enum State { OPEN, WAITING, CLOSED }
}
