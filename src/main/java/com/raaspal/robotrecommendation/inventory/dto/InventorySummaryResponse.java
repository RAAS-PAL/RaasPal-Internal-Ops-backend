package com.raaspal.robotrecommendation.inventory.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The RIMS dashboard header: what is stocked, what is short, what it is worth.
 *
 * <p>{@code lowStockItems} is capped by the service rather than returned whole. A
 * dashboard tile shows the worst offenders and links through to the filtered list;
 * shipping every short item would make the payload grow exactly when the warehouse
 * is in the worst state to load a slow page.
 */
public record InventorySummaryResponse(

        /** Distinct active SKUs. */
        long totalItems,

        /** Items at or below their own reorder point — the alert count. */
        long lowStockCount,

        /** Sum of unitCost x quantityOnHand, over items that have a cost. */
        BigDecimal stockValue,

        /**
         * Robots the warehouse holds and can sell or deploy, from
         * {@code robot_inventory_temp} — not from the fleet in {@code robot_units},
         * which counts machines already at customers.
         */
        long robotsInStock,

        /**
         * Robots out on trial. Reported separately from {@link #robotsInStock} rather
         * than folded in: a demo unit is on the premises and must be visible, but it
         * is promised to a customer trial. One combined figure is how a robot gets
         * offered to two people at once.
         */
        long robotsOnDemo,

        /** The shortest items first, capped. */
        List<InventoryItemResponse> lowStockItems
) {
}
