package com.raaspal.robotrecommendation.robotunit.entity;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A physical robot unit identified by its manufacturer serial number.
 */
@Entity
@Table(name = "robot_units")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RobotUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "serial_number", nullable = false, unique = true, length = 100)
    private String serialNumber;

    @Column(nullable = false, length = 100)
    private String brand;

    @Column(length = 255)
    private String model;

    @Column(length = 255)
    private String name;

    /** IN_STOCK | RENT | SOLD — see {@link RobotUnitStatus}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RobotUnitStatus status = RobotUnitStatus.IN_STOCK;

    /**
     * Hardware revision of this unit ("V1.1"), parsed from the imported model text.
     *
     * <p>Secondary information. It was added on the assumption that revisions share
     * a model, which the Gausium datasheet then disproved — Phantas v1.1 and v1.3
     * differ by 17 kg and 2.6x the scrub throughput, so they are separate catalogue
     * models. {@code robotId} is the authoritative link; this is raw provenance and
     * the only revision signal for units not yet matched to a model.
     */
    @Column(length = 50)
    private String version;

    /**
     * CLEANING | DELIVERY | MOWING | SECURITY. Denormalised because
     * {@code robotId} is nullable and RIMS still needs to filter by type.
     *
     * <p>Defaults to CLEANING, matching V28's backfill and the fact that all 152
     * units are cleaning robots. The column is NOT NULL in the database, so without
     * a default every caller — including the partner test fixtures — has to supply
     * one, and forgetting fails at insert time rather than compile time.
     *
     * <p>⚠️ The flip side: register a delivery robot without setting the type and it
     * silently becomes CLEANING. {@code RegisterRobotRequest} accepts it explicitly
     * for that reason.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "robot_type", nullable = false, length = 20)
    @Builder.Default
    private RobotType robotType = RobotType.CLEANING;

    /**
     * The catalogue model this unit is an instance of, and the route to its specs.
     *
     * <p>Nullable: 53 of the 152 existing units carry free-text models with no
     * catalogue row yet. Mapped as an id rather than a {@code @ManyToOne} so that
     * loading a unit never drags the whole {@code Robot} graph behind it — the
     * telemetry sync walks every active unit nightly.
     */
    @Column(name = "robot_id")
    private UUID robotId;

    /** Which of our own premises the unit sits in. Only meaningful while IN_STOCK. */
    @Column(length = 128)
    private String location;


    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}