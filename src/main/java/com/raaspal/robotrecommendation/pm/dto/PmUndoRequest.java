package com.raaspal.robotrecommendation.pm.dto;

/**
 * Undo one move.
 *
 * @param confirmCompleted as for a move: the person confirmed changing a visit monday
 *                         shows as completed
 */
public record PmUndoRequest(Boolean confirmCompleted) {

    public boolean confirmed() {
        return Boolean.TRUE.equals(confirmCompleted);
    }
}
