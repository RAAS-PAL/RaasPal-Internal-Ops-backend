package com.raaspal.robotrecommendation.inventory.dto;

import com.raaspal.robotrecommendation.inventory.entity.MovementType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Change a stock level.
 *
 * <p>The caller states a <em>change</em>, never a new total. Asking for "set this
 * to 47" throws away the reason it moved and races with anyone else counting the
 * same shelf; "-1, miscount" survives both. A stocktake correction is an
 * {@link MovementType#ADJUSTMENT} carrying the difference.
 */
public record StockMovementRequest(

        @NotNull MovementType movementType,

        /**
         * Signed. Positive adds, negative removes. Rejected when zero — a movement
         * that changes nothing is a mistake, not a record.
         */
        @NotNull Integer quantityChange,

        /** Which physical robot consumed the part. Null for a supplier delivery. */
        UUID robotUnitId,

        @Size(max = 2000) String note
) {
}
