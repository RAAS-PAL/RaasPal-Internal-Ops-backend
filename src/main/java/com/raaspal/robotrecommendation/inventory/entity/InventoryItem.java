package com.raaspal.robotrecommendation.inventory.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
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
     * The part number — what RIMS labels 'Part number' and what the warehouse's
     * stock sheet calls Part Code. Holds the manufacturer's code when there is
     * one; generated as {@code INV-000001} when the operator leaves it blank, so
     * a part handed over with no visible number is still searchable.
     *
     * <p>UNIQUE, so two parts cannot share a number. Note the warehouse sheet does
     * contain repeats (the short- and long-shaft casters both read A0308010021),
     * and those are rejected on the second save rather than silently merged.
     */
    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    /** The manufacturer's code. Optional, and deliberately not unique — the same
     *  part can come from two suppliers under different codes. */
    @Column(name = "supplier_part_no", length = 64)
    private String supplierPartNo;

    @Column(length = 64)
    private String barcode;

    /**
     * Photo of the part, as a base64 {@code data:} URI or an http(s) URL.
     * <p>
     * Never returned in a list response — {@code InventoryItemResponse} carries
     * {@code hasImage} and the browser fetches the bytes from
     * {@code /inventory/items/{id}/image}. At ~320 parts, inlining photos would
     * make the parts list a multi-megabyte payload that cannot be cached.
     */
    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(nullable = false, length = 255)
    private String name;

    /** BRUSH | FILTER | BATTERY | SQUEEGEE | ... Free text rather than an enum:
     *  new categories appear as new stock arrives, and each one should not need
     *  a code change and a redeploy. */
    @Column(nullable = false, length = 64)
    private String category;

    /**
     * The warehouse robots this part fits ({@code robot_inventory_temp} ids).
     * Empty means universal — detergent, cloths — usable with any robot.
     * <p>
     * A set of ids over a join table rather than a single FK, because the
     * relationship is genuinely many-to-many: one filter fits both the M50 and
     * the M75. And it points at the RIMS warehouse list, not the {@code robots}
     * catalogue — only 15% of the warehouse's models exist in the catalogue
     * (measured before V34), so a catalogue link would leave most parts
     * unlinkable to the robots they are actually bought for.
     * <p>
     * An {@code @ElementCollection} of ids rather than an entity association:
     * the link has no attributes of its own, and this keeps
     * {@code RobotStockEntry} free of a back-reference it has no use for.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "inventory_item_robots", joinColumns = @JoinColumn(name = "inventory_item_id"))
    @Column(name = "robot_stock_id", nullable = false)
    // SUBSELECT is load-bearing, not tuning: without it a 200-row parts list
    // lazily loads 200 link sets one query each — 200 round trips to a pooled
    // Supabase that allows 15 connections. With it, touching the first set loads
    // every set in the persistence context in one query.
    @org.hibernate.annotations.Fetch(org.hibernate.annotations.FetchMode.SUBSELECT)
    @Builder.Default
    private Set<UUID> robotStockIds = new HashSet<>();

    /**
     * Always "EA". The column is NOT NULL and kept for now, but the field was
     * dropped from the form and the API: RAASPAL counts every part in pieces, so
     * asking for a unit bought nothing and printing "9 EA" everywhere was noise.
     * This default is the only thing that writes it.
     */
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
