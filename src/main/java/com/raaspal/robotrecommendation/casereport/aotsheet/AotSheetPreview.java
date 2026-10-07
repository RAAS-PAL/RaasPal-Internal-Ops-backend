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
 * @param duplicateIds        ids on more than one row; one on a row that is not closed
 *                            stops the sync, one on closed rows only is skipped
 * @param openDateUnreadable  rows whose open-date cell is not a date
 * @param open                rows read as open; null while closure is not configured
 * @param closed              rows read as closed; null while closure is not configured
 * @param sample              the first rows as the mapping reads them
 * @param rowsWithoutIdList   the row numbers behind {@code rowsWithoutId}, the first 50,
 *                            so they can be found and filled in
 * @param duplicates          each repeated id with the rows it is on, those that stop
 *                            the sync first; the first 50
 * @param colourColumn        the column whose colour was read - before one is saved, the
 *                            guessed status column; null when there is none
 * @param colours             the colours that column uses, most rows first, and whether
 *                            each is one chosen to mean closed
 * @param waiting             rows whose status says closed but whose colour does not:
 *                            waiting for their part; null unless colours decide closure
 * @param suggested           the columns the setup guesses for each field, from their
 *                            headers, so nobody has to type a column name
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
                              List<AotSheetCase> sample,
                              List<Integer> rowsWithoutIdList,
                              List<DuplicateId> duplicates,
                              String colourColumn,
                              List<ColourCount> colours,
                              Integer waiting,
                              Suggested suggested) {

    /** The column guessed for each field; null when no header looks like it. */
    public record Suggested(String rowId, String openDate, String status) {
    }

    /**
     * An id on more than one row, and the rows.
     *
     * @param blocksSync at least one of the rows is not closed, so the sync refuses; when
     *                   all are closed they are only skipped
     */
    public record DuplicateId(String id, List<Integer> rows, boolean blocksSync) {
    }

    /** A background colour, {@code #rrggbb}, on how many rows, and whether it means closed. */
    public record ColourCount(String colour, int rows, boolean closed) {
    }
}
