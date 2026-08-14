package com.raaspal.robotrecommendation.inventory.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A stocked part or consumable — brushes, filters, squeegees, batteries.
 *
 * <p>Only <em>fungible</em> stock lives here. Whole robots do not: each has a serial
 * number, a deployment history and telemetry, so they stay in {@code robot_units}
 * and are counted rather than stored. One brush is interchangeable with the next;
 * one robot is not interchangeable with another.
 */
@Entity
@Table(name = "inventory_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Our own code, always present — generated as {@code INV-000001} when the
     * operator leaves it blank. Distinct from {@link #supplierPartNo}: not every
     * part has a manufacturer code, but every part needs something searchable.
     */
    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    /** The manufacturer's code. Optional, and deliberately not unique — the same
     *  part can come from two suppliers under different codes. */
    @Column(name = "supplier_part_no", length = 64)
    private String supplierPartNo;

    @Column(length = 64)
    private String barcode;

    @Column(nullable = false, length = 255)
    private String name;

    /** BRUSH | FILTER | BATTERY | SQUEEGEE | ... Free text rather than an enum:
     *  new categories appear as new stock arrives, and each one should not need
     *  a code change and a redeploy. */
    @Column(nullable = false, length = 64)
    private String category;

    /** The robot MODEL this part fits, or null for universal items such as
     *  detergent. Points at the model because every Phantas takes the same brush. */
    @Column(name = "robot_id")
    private UUID robotId;

    /** EA | L | M | BOX | SET. Without it "5" is meaningless. */
    @Column(name = "unit_of_measure", nullable = false, length = 16)
    @Builder.Default
    private String unitOfMeasure = "EA";

    /**
     * Cached balance. {@code stock_movements} is the source of truth; this exists so
     * the stock list renders without aggregating the ledger on every request.
     *
     * <p><strong>Never set this directly.</strong> {@code InventoryService.recordMovement}
     * is the only writer, and it updates this in the same transaction that appends
     * the movement. Any other write silently desynchronises the two.
     */
    @Column(name = "quantity_on_hand", nullable = false)
    @Builder.Default
    private Integer quantityOnHand = 0;

    /** Alert threshold. At or below this, the item shows on the RIMS dashboard. */
    @Column(name = "reorder_point", nullable = false)
    @Builder.Default
    private Integer reorderPoint = 10;

    /** How many to buy when reordering — a separate decision from when to reorder. */
    @Column(name = "reorder_quantity", nullable = false)
    @Builder.Default
    private Integer reorderQuantity = 0;

    /** Last known cost. Not moving-average or FIFO — good enough for a stock
     *  valuation figure, not for accounting. */
    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(length = 128)
    private String location;

    /** Soft delete. An item with movement history cannot be removed without
     *  destroying the audit trail, so discontinued parts drop out of lists instead. */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** True when this item should appear in the low-stock alert. */
    public boolean isLowStock() {
        return quantityOnHand != null && reorderPoint != null && quantityOnHand <= reorderPoint;
    }
}
