package com.raaspal.robotrecommendation.casereport.adapters.googlesheet.dto;

import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.SheetCells;

import java.time.LocalDate;
import java.util.Map;

/**
 * One data row of a tab, keyed by header.
 *
 * @param rowNumber the row's number as the sheet shows it (1-based), so a problem can be
 *                  reported as "row 214" and found by eye. Not an identity: inserting or
 *                  sorting rows changes it.
 * @param cells     non-empty cells only, header to unformatted value, in column order
 */
public record SheetRow(int rowNumber, Map<String, Object> cells) {

    public Object get(String header) {
        return header == null ? null : cells.get(header);
    }

    public String text(String header) {
        return SheetCells.text(get(header));
    }

    public LocalDate date(String header) {
        return SheetCells.date(get(header));
    }
}
