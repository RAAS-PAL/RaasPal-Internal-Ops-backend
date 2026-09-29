package com.raaspal.robotrecommendation.casereport.aotsheet;

import java.util.List;

/**
 * What the sync would see, without it writing anything.
 *
 * @param shareWith           the service account's address - share the sheet with it as
 *                            a Viewer; null when no readable key is configured
 * @param headers             every column the sheet returned, hidden ones included, as
 *                            the mapping must name them
 * @param missingHeaders      configured column names the sheet does not have
 * @param notReadyForSync     configuration the sync still needs; empty when ready
 * @param rowsWithoutId       rows the sync would skip, their id cell empty
 * @param duplicateIds        ids on more than one row; any of these stops the sync
 * @param openDateUnreadable  rows whose open-date cell is not a date
 * @param open                rows read as open; null while closure is not configured
 * @param closed              rows read as closed; null while closure is not configured
 * @param sample              the first rows as the mapping reads them
 */
public record AotSheetPreview(String spreadsheetId,
                              String tab,
                              String shareWith,
                              boolean syncEnabled,
                              List<String> headers,
                              List<String> missingHeaders,
                              List<String> notReadyForSync,
                              int rows,
                              int rowsWithoutId,
                              List<String> duplicateIds,
                              int openDateUnreadable,
                              Integer open,
                              Integer closed,
                              List<AotSheetCase> sample) {
}
