package com.raaspal.robotrecommendation.inventory.dto;

import com.raaspal.robotrecommendation.inventory.entity.InventoryItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A stocked part as RIMS sees it.
 *
 * <p>{@code lowStock} is computed here rather than left to the client. The rule —
 * at or below the item's own reorder point — decides the dashboard alert, and two
 * implementations of it (one server-side for the summary count, one in TypeScript
 * for the row badge) would eventually disagree about which items are short.
 */
public record InventoryItemResponse(
        UUID id,
        String sku,
        String supplierPartNo,
        String barcode,
        String name,
        String category,
        UUID robotId,
        String robotModel,
        String unitOfMeasure,
        Integer quantityOnHand,
        Integer reorderPoint,
        Integer reorderQuantity,
        BigDecimal unitCost,
        String location,
        Boolean isActive,
        boolean lowStock,
        LocalDateTime updatedAt
) {

    /** @param robotModel resolved by the service; null for universal items. */
    public static InventoryItemResponse from(InventoryItem i, String robotModel) {
        return new InventoryItemResponse(
                i.getId(),
                i.getSku(),
                i.getSupplierPartNo(),
                i.getBarcode(),
                i.getName(),
                i.getCategory(),
                i.getRobotId(),
                robotModel,
                i.getUnitOfMeasure(),
                i.getQuantityOnHand(),
                i.getReorderPoint(),
                i.getReorderQuantity(),
                i.getUnitCost(),
                i.getLocation(),
                i.getIsActive(),
                i.isLowStock(),
                i.getUpdatedAt());
    }
}
