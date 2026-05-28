package com.raaspal.robotrecommendation.requirement.entity;

import com.raaspal.robotrecommendation.common.enums.*;
import com.raaspal.robotrecommendation.file.entity.FileUpload;
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
 *
 * Three intake paths all produce the same structured entity:
 *   WEB_FORM       → fields filled directly via the wizard
 *   CSV_IMPORT     → service parses CSV rows into this entity
 *   FILE_EXTRACTED → AI reads a PDF/image and pre-fills fields; team reviews
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

    /** Directs the engine to the correct spec table and scoring config. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "robot_type", nullable = false, length = 20)
    private RobotType robotType = RobotType.CLEANING;

    @Column(nullable = false)
    private String title;

    /** Free-text needs description. */
    @Column(columnDefinition = "TEXT")
    private String description;

    // ── Hard constraints ──────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Environment environment;

    /**
     * Cleaning functions the customer needs (e.g. sweep, scrub, mop, vacuum).
     * Stored as a PostgreSQL TEXT array.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "cleaning_functions", columnDefinition = "text[]")
    private String[] cleaningFunctions;

    /**
     * Floor surface types at the site (e.g. marble, ceramic, vinyl).
     * Stored as a PostgreSQL TEXT array.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "floor_types", columnDefinition = "text[]")
    private String[] floorTypes;

    /** Narrowest doorway or corridor the robot must fit through. */
    @Column(name = "min_passable_width_mm")
    private Integer minPassableWidthMm;

    // ── Soft constraints ──────────────────────────────────────────────────────

    /** Area to clean per shift. */
    @Column(name = "coverage_area_sqm")
    private Integer coverageAreaSqm;

    /** Coarse budget guide mapped to robot price_band. */
    @Enumerated(EnumType.STRING)
    @Column(name = "budget_band", length = 10)
    private BudgetBand budgetBand;

    @Column(name = "priority_notes", columnDefinition = "TEXT")
    private String priorityNotes;

    // ── Input source tracking ─────────────────────────────────────────────────

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "input_source", nullable = false, length = 20)
    private InputSource inputSource = InputSource.WEB_FORM;

    /**
     * The CSV or PDF/image file that generated this requirement.
     * Null when input_source = WEB_FORM.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_file_id")
    private FileUpload sourceFile;

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
