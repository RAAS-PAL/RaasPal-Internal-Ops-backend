package com.raaspal.robotrecommendation.pm.dto;

import java.time.LocalDate;

/**
 * Move one visit.
 *
 * @param planDate         the new plan date
 * @param seenPlanDate     the plan date the person was looking at when they chose; if monday
 *                         holds a different one by now, nothing is written
 * @param confirmCompleted the person confirmed moving a visit monday shows as completed;
 *                         without it such a visit comes back as needing confirmation
 */
public record PmPlanDateRequest(LocalDate planDate, LocalDate seenPlanDate, Boolean confirmCompleted) {

    public boolean confirmed() {
        return Boolean.TRUE.equals(confirmCompleted);
    }
}
