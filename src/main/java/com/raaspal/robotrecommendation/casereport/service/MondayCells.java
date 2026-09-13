package com.raaspal.robotrecommendation.casereport.service;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Cell values as the boards write them, read leniently. */
@Slf4j
final class MondayCells {

    private MondayCells() {
    }

    /**
     * A monday date column, or null.
     *
     * <p>Lenient by design. An unparseable date leaves the day count and the SLA blank,
     * which surfaces the row for a human; throwing would fail the whole report over one
     * malformed cell, on a board people edit by hand all day.
     */
    static LocalDate date(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            log.warn("Unparseable date on a monday board: '{}'", text);
            return null;
        }
    }

    /** Null for a blank cell, the trimmed text otherwise. */
    static String text(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
