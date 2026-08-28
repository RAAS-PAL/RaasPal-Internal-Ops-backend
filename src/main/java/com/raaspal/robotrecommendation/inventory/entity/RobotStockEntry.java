package com.raaspal.robotrecommendation.inventory.entity;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A robot the warehouse holds, as the inventory team records it.
 *
 * <p>Maps to {@code robot_inventory_temp} (V30) — the class is named for what it is
 * rather than for the table, but grep for either and you land here.
 *
 * <p><strong>Standalone by design.</strong> No association to {@code RobotUnit} or
 * {@code Robot}: not a {@code @ManyToOne}, not even a loose id column. The fleet
 * record carries 152 real machines with telemetry, deployments and partner-API
 * scoping attached, and a hand-kept warehouse list has no business reaching into it.
 * When the two are eventually reconciled that will be a deliberate migration, not an
 * accident of a foreign key someone added early.
 *
 * <p>That independence is also why {@link #quantity} can be a plain number. On
 * {@code robot_units} it could not be: a serial number is the robot's identity across
 * telemetry, monthly reports and the partner API, so a count with no serials behind
 * it would drift from the fleet the first time one was deployed. This table makes no
 * claim to be the fleet, so counting is the honest shape for it.
 */
@Entity
@Table(name = "robot_inventory_temp")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RobotStockEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "robot_type", nullable = false, length = 20)
    @Builder.Default
    private RobotType robotType = RobotType.CLEANING;

    @Column(nullable = false, length = 100)
    private String brand;

    @Column(nullable = false, length = 255)
    private String model;

    /**
     * Free text, because it carries two things that look identical on a shelf: a
     * revision ("v1.3") and a configuration ("Roller Brush"). Constraining it to one
     * of those would force the other into the model name.
     */
    @Column(length = 100)
    private String version;

    /** http(s) URL or a base64 {@code data:} URI. Size is capped in the service. */
    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 0;

    /**
     * What the quantity was before the last change to it (V31).
     *
     * <p>One step back, not a history: enough to undo "typed 3, meant 30" noticed
     * straight away. Written <em>only</em> when the quantity actually changes —
     * saving an edit to the location must not overwrite the backup with the current
     * number, or an unrelated edit destroys the value worth keeping.
     */
    @Column(name = "previous_quantity")
    private Integer previousQuantity;

    /** When that backup was taken. "Previously 12" means little without a date. */
    @Column(name = "previous_quantity_at")
    private LocalDateTime previousQuantityAt;

    /**
     * Where this shelf stands: IN_STOCK, DEMO, UNDER_REPAIR or RETURNED_FROM_CUSTOMER.
     *
     * <p>Reuses the fleet enum, but only the values
     * {@link RobotUnitStatus#isStockRoomStatus} admits. RENT and SOLD describe a
     * customer agreement and are rejected in the service, because a robot at a
     * customer is the fleet business and has no row here.
     *
     * <p>Widened to 32 in V37: RETURNED_FROM_CUSTOMER does not fit in 20 characters.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private RobotUnitStatus status = RobotUnitStatus.IN_STOCK;

    /**
     * Boxed or not, or null where nobody has recorded it.
     *
     * <p>Not part of the row identity, so one value covers the whole quantity on the
     * row. See {@link Packaging} and V37 for why that trade was made.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Packaging packaging;

    @Column(length = 128)
    private String location;

    @Column(columnDefinition = "TEXT")
    private String note;

    /** Who last changed it. Not a foreign key — see the class note on independence. */
    @Column(name = "updated_by")
    private UUID updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * "Gausium Phantas v1.3" — assembled once here so every surface agrees.
     * Moved from {@code RobotStockEntryResponse} when inventory items started
     * naming their linked robots too; two assemblies would eventually differ.
     */
    public String displayName() {
        return (brand + " " + model
                + (version == null || version.isBlank() ? "" : " " + version)).trim();
    }
}
