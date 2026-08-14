package com.raaspal.robotrecommendation.inventory.dto;

import com.raaspal.robotrecommendation.inventory.entity.MovementType;
import com.raaspal.robotrecommendation.inventory.entity.StockMovement;

import java.time.LocalDateTime;
import java.util.UUID;

/** One line of stock history. */
public record StockMovementResponse(
        UUID id,
        UUID inventoryItemId,
        String itemName,
        String itemSku,
        MovementType movementType,
        Integer quantityChange,
        Integer balanceAfter,
        UUID robotUnitId,
        String robotSerialNumber,
        String note,
        UUID createdBy,
        String createdByName,
        LocalDateTime createdAt
) {

    /** Names are resolved by the service; any of them may be null. */
    public static StockMovementResponse from(StockMovement m,
                                             String itemName,
                                             String itemSku,
                                             String robotSerialNumber,
                                             String createdByName) {
        return new StockMovementResponse(
                m.getId(),
                m.getInventoryItemId(),
                itemName,
                itemSku,
                m.getMovementType(),
                m.getQuantityChange(),
                m.getBalanceAfter(),
                m.getRobotUnitId(),
                robotSerialNumber,
                m.getNote(),
                m.getCreatedBy(),
                createdByName,
                m.getCreatedAt());
    }
}
