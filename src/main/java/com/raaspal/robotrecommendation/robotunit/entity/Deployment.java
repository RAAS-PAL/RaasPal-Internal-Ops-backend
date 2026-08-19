package com.raaspal.robotrecommendation.robotunit.entity;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Links a RobotUnit to the CustomerProfile and site where it is installed.
 */
@Entity
@Table(name = "deployments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Deployment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "robot_unit_id", nullable = false)
    private RobotUnit robotUnit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_profile_id", nullable = false)
    private CustomerProfile customerProfile;

    @Column(length = 255)
    private String site;

    /**
     * Distributor / service partner (e.g. PCS) servicing this robot; null =
     * RAASPAL-direct. The partner API scopes all reads through this column.
     * Plain UUID (no relation) to keep the partner module decoupled.
     */
    @Column(name = "partner_id")
    private UUID partnerId;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /** How often this robot's report is sent on a schedule. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "report_cadence", nullable = false, length = 20)
    private ReportCadence reportCadence = ReportCadence.MONTHLY;

    /**
     * When this robot started working for this customer under contract.
     * <p>
     * Monthly reports clip to it, so a robot deployed mid-month does not report work
     * it did before the contract began. Null means unknown and reports the whole month.
     * <p>
     * Distinct from {@link #deployedAt}, which is set to now() at registration and so
     * records when the robot was entered into the system, not when its contract started.
     */
    @Column(name = "contract_start_date")
    private LocalDate contractStartDate;

    @Column(name = "deployed_at", nullable = false)
    private LocalDateTime deployedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}