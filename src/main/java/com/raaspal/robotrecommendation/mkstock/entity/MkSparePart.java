package com.raaspal.robotrecommendation.mkstock.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A spare part RAAS PAL holds for MK. Maps to {@code mk_spare_part} (V57).
 *
 * <p>{@code quantityOnHand} is a cached balance: only {@code MkStockService#recordMovement}
 * changes it, in the same transaction that appends the {@link MkStockMovement}.
 */
@Entity
@Table(name = "mk_spare_part")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MkSparePart {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "part_no", nullable = false, columnDefinition = "TEXT")
    private String partNo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "robot_model", columnDefinition = "TEXT")
    private String robotModel;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String unit = "pcs";

    /** Warn at or below this; 0 = no warning. */
    @Column(name = "min_level", nullable = false)
    @Builder.Default
    private int minLevel = 0;

    @Column(columnDefinition = "TEXT")
    private String location;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "quantity_on_hand", nullable = false)
    @Builder.Default
    private int quantityOnHand = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_by", nullable = false, columnDefinition = "TEXT")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /** OUT when empty, LOW at or below the minimum (when one is set), else OK. */
    public String stockStatus() {
        if (quantityOnHand <= 0) return "OUT";
        if (minLevel > 0 && quantityOnHand <= minLevel) return "LOW";
        return "OK";
    }
}
