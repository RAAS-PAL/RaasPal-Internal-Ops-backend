package com.raaspal.robotrecommendation.requirement.entity;

import com.raaspal.robotrecommendation.common.enums.BudgetBand;
import com.raaspal.robotrecommendation.common.enums.Environment;
import com.raaspal.robotrecommendation.common.enums.RequirementStatus;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Central intake entity capturing what the customer needs.
 * Ownership is anchored to customer_profile_id — all ownership checks use:
 *   requirement.customerProfile.id == currentUser.customerProfileId
 *
 * The structured fields drive the hard filter and scoring.
 * The free-text description feeds the AI semantic matching.
 * As Raas Pal adds delivery/security robots, robot_type directs the engine
 * to the correct spec table, but ownership and status structure stay identical.
 */
@Entity
@Table(name = "requirements")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Requirement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_profile_id", nullable = false)
    private CustomerProfile customerProfile;

    /** Directs the engine to the correct spec table / scoring config. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "robot_type", nullable = false, length = 20)
    private RobotType robotType = RobotType.CLEANING;

    @Column(nullable = false)
    private String title;

    /** Free-text needs — embedded as a vector for AI semantic matching. */
    @Column(columnDefinition = "TEXT")
    private String description;

    // ── Hard constraints (drive the hard filter) ──────────────────────────────

    /** Site environment — hard constraint for environment-fit scoring. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Environment environment;

    /**
     * Which cleaning functions the customer needs (e.g. sweep, scrub, mop, vacuum).
     * Multi-select from the wizard; stored as a PostgreSQL TEXT array.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "cleaning_functions", columnDefinition = "text[]")
    private String[] cleaningFunctions;

    /**
     * Floor surface types at the customer's site (e.g. marble, ceramic, vinyl).
     * Multi-select from the wizard; stored as a PostgreSQL TEXT array.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "floor_types", columnDefinition = "text[]")
    private String[] floorTypes;

    /** Narrowest doorway/corridor the robot must pass through. */
    @Column(name = "min_passable_width_mm")
    private Integer minPassableWidthMm;

    // ── Soft constraints (influence scoring) ──────────────────────────────────

    /** Area to clean per shift — drives coverage capacity scoring. */
    @Column(name = "coverage_area_sqm")
    private Integer coverageAreaSqm;

    /** Coarse budget guide — soft constraint mapped to robot price_band. */
    @Enumerated(EnumType.STRING)
    @Column(name = "budget_band", length = 10)
    private BudgetBand budgetBand;

    @Column(name = "priority_notes", columnDefinition = "TEXT")
    private String priorityNotes;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequirementStatus status = RequirementStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}