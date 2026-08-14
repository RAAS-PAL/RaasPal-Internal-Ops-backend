package com.raaspal.robotrecommendation.inventory.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One movement of stock in or out — the ledger behind
 * {@link InventoryItem#getQuantityOnHand()}.
 *
 * <p><strong>Append-only.</strong> Rows are never updated or deleted; a mistake is
 * corrected by recording a compensating {@link MovementType#ADJUSTMENT}. That is
 * what makes this an audit trail rather than a log file, and it is why there is no
 * {@code updatedAt} column — the absence is deliberate, not an oversight.
 */
@Entity
@Table(name = "stock_movements")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "inventory_item_id", nullable = false)
    private UUID inventoryItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20)
    private MovementType movementType;

    /**
     * Signed: {@code +50} received, {@code -2} issued. One signed column means the
     * balance is a plain {@code SUM}; separate in/out columns could disagree with
     * each other and would need a {@code CASE} in every query.
     */
    @Column(name = "quantity_change", nullable = false)
    private Integer quantityChange;

    /**
     * Stock level immediately after this movement. Derivable, but stored so the
     * history screen shows a running total without re-summing — and so a mismatch
     * against the running sum exposes any write that bypassed the service.
     */
    @Column(name = "balance_after", nullable = false)
    private Integer balanceAfter;

    /**
     * Which physical robot consumed the part. Null for a supplier delivery.
     *
     * <p>Answers "how many brushes has this serial used this year?" — intelligence
     * that cannot be reconstructed afterwards if it is not captured at the time.
     */
    @Column(name = "robot_unit_id")
    private UUID robotUnitId;

    @Column(columnDefinition = "TEXT")
    private String note;

    /** Who recorded it. Meaningless while the whole team shares one login, which
     *  is why real accounts matter before this goes into daily use. */
    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
