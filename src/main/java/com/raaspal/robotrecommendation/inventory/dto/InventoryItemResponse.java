package com.raaspal.robotrecommendation.inventory.dto;

import com.raaspal.robotrecommendation.inventory.entity.InventoryItem;

import java.time.LocalDateTime;
import java.util.List;
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
        String barcode,
        String name,
        String category,

        /** The warehouse robots this part fits. Empty = universal. */
        List<LinkedRobot> robots,

        Integer quantityOnHand,
        Integer reorderPoint,
        Integer reorderQuantity,
        Boolean isActive,
        boolean lowStock,
        LocalDateTime updatedAt
) {

    /**
     * A robot this part fits — id to link to its page, display name to render.
     * The name is resolved by the service ("Gausium Phantas v1.3"); a link whose
     * robot was since deleted is dropped rather than shown nameless.
     */
    public record LinkedRobot(UUID id, String displayName) {
    }

    /** @param robots resolved by the service; empty for universal items. */
    public static InventoryItemResponse from(InventoryItem i, List<LinkedRobot> robots) {
        return new InventoryItemResponse(
                i.getId(),
                i.getSku(),
                i.getBarcode(),
                i.getName(),
                i.getCategory(),
                robots,
                i.getQuantityOnHand(),
                i.getReorderPoint(),
                i.getReorderQuantity(),
                i.getIsActive(),
                i.isLowStock(),
                i.getUpdatedAt());
    }
}
