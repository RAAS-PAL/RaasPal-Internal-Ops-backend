package com.raaspal.robotrecommendation.pm.dto;

import java.time.LocalDate;

/**
 * Move one visit's Action date - the only date the planner changes (user, 2026-10-08).
 *
 * @param date             the new Action date
 * @param seenDate         the Action date the person was looking at when they chose (null
 *                         for none); if monday holds a different one by now, nothing is written
 * @param confirmCompleted the person confirmed moving a visit monday shows as completed;
 *                         without it such a visit comes back as needing confirmation
 */
public record PmPlanDateRequest(LocalDate date, LocalDate seenDate, Boolean confirmCompleted) {
    public boolean confirmed() {
        return Boolean.TRUE.equals(confirmCompleted);
    }
}
