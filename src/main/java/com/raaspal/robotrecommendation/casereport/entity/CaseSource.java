package com.raaspal.robotrecommendation.casereport.entity;

/**
 * Where a case ticket was read from.
 *
 * <p>Exists because the AOT data lives in <em>both</em> a monday.com board and a Google
 * Sheet the RE team keeps, and which one wins is not settled. V38 is deliberately
 * source-neutral throughout for the same reason — every column says "source", never
 * "monday" — so that settling it later is a config change rather than a migration plus
 * a rename across every generator.
 */
public enum CaseSource {

    /** A monday.com board, read through {@code MondayBoardReader}. */
    MONDAY,

    /**
     * The RE team's Google Sheet. Not implemented — present so the column's contract is
     * visible in code rather than only in the migration comment.
     */
    GOOGLE_SHEET
}
