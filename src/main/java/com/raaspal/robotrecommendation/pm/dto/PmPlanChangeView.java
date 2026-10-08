package com.raaspal.robotrecommendation.pm.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the planner's "Recent moves".
 *
 * @param itemId    the visit's monday subitem id, as monday's "Item ID" column shows it
 * @param siteName  the contract's item name; null when the visit is no longer in the mirror
 * @param undone    a later undo reversed this move
 * @param undoable  a move that is the visit's latest change and not undone yet; whether
 *                  monday still agrees is only known when the undo is tried
 * @param field     which date it moved: ACTION, or PLAN for moves before 2026-10-08
 */
public record PmPlanChangeView(UUID id, UUID visitId, String itemId, String visitName, String siteName, String serviceLine,
                               String action, LocalDate oldPlanDate, LocalDate newPlanDate,
                               boolean confirmedCompleted, String changedBy, OffsetDateTime changedAt,
                               boolean undone, boolean undoable, String field) {
}
