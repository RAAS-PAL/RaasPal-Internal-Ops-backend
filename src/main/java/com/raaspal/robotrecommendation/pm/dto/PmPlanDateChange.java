package com.raaspal.robotrecommendation.pm.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What happened to a move or an undo.
 *
 * <p>The outcomes other than {@link Outcome#MOVED} are answers, not errors: each says
 * why nothing was written, in a form the planner can act on and translate.
 *
 * @param field        which date it was: ACTION for a move, or for the undo of an older
 *                     plan-date move, PLAN
 * @param previousDate the date monday held before; null when it had none
 * @param date         the date monday holds now
 * @param changeId     the log row this wrote, which an undo names; null when nothing was written
 */
public record PmPlanDateChange(UUID visitId, Outcome outcome, LocalDate previousDate, LocalDate date,
                               UUID changeId, String field) {
    public enum Outcome {
        /** Written to monday and to the mirror, and logged. */
        MOVED,
        /** The new date was the date monday already had; nothing was written. */
        UNCHANGED,
        /** monday's date differs from the one the person saw; nothing was written, the mirror now has monday's. */
        CHANGED_ON_MONDAY,
        /** monday shows the visit as completed; nothing was written until the person confirms. */
        NEEDS_CONFIRMATION
    }
}
