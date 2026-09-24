package com.raaspal.robotrecommendation.mkstock.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One stock in, out or correction for an MK part. Maps to {@code mk_stock_movement} (V57),
 * which is append-only: rows are never updated or deleted.
 */
@Entity
@Table(name = "mk_stock_movement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MkStockMovement {

    public static final String IN = "IN";
    public static final String OUT = "OUT";
    public static final String ADJUST = "ADJUST";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    /** IN | OUT | ADJUST */
    @Column(name = "movement_type", nullable = false, columnDefinition = "TEXT")
    private String movementType;

    /** Signed: +5 in, -2 out. */
    @Column(name = "quantity_change", nullable = false)
    private int quantityChange;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    /** Required for OUT and ADJUST. */
    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String reference;

    @Column(name = "moved_on", nullable = false)
    private LocalDate movedOn;

    @Column(name = "created_by", nullable = false, columnDefinition = "TEXT")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
