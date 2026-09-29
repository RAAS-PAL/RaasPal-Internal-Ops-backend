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
                           Boolean closed) {
}
