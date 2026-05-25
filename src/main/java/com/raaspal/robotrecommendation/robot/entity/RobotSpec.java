package com.raaspal.robotrecommendation.robot.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Cleaning-robot specifications parsed from the raw datasheet CSV (Phase 2 import).
 *
 * All columns are nullable — a blank cell in the datasheet becomes NULL here,
 * never zero. The recommendation engine renormalises weights when specs are missing.
 *
 * Columns are grouped into the six scoring dimensions:
 *   1. Capability         — cleaning functions + efficiency
 *   2. Size & access      — physical dimensions, passable width, slope
 *   3. Coverage capacity  — tank sizes, battery, speed
 *   4. Floor suitability  — 17 floor-type booleans + 6 layout-size flags
 *   5. Environment fit    — indoor/outdoor, IP rating, HEPA
 *   6. Operational quality— noise, navigation type, auto-dock, spot AI
 */
@Entity
@Table(name = "robot_specs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RobotSpec {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "robot_id", nullable = false, unique = true)
    private Robot robot;

    // ── Physical ──────────────────────────────────────────────────────────────
    @Column(name = "weight_kg", precision = 8, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "length_mm")
    private Integer lengthMm;

    @Column(name = "width_mm")
    private Integer widthMm;

    @Column(name = "height_mm")
    private Integer heightMm;

    @Column(name = "cleaning_width_mm")
    private Integer cleaningWidthMm;

    @Column(name = "brush_pressure_kg", precision = 6, scale = 2)
    private BigDecimal brushPressureKg;

    @Column(name = "vacuum_pressure_kpa", precision = 6, scale = 2)
    private BigDecimal vacuumPressureKpa;

    // ── Dimension 1 – Capability: cleaning functions ──────────────────────────
    @Column(name = "func_sweep")
    private Boolean funcSweep;

    @Column(name = "func_sweep_vacuum")
    private Boolean funcSweepVacuum;

    @Column(name = "func_dry_mop")
    private Boolean funcDryMop;

    @Column(name = "func_wet_mop")
    private Boolean funcWetMop;

    @Column(name = "func_roller_scrub")
    private Boolean funcRollerScrub;

    @Column(name = "func_disc_scrub")
    private Boolean funcDiscScrub;

    // ── Dimension 1 – Capability: cleaning efficiency (m²/h per mode) ─────────
    @Column(name = "efficiency_sweep_sqm_h")
    private Integer efficiencySweepSqmH;

    @Column(name = "efficiency_scrub_sqm_h")
    private Integer efficiencyScrubSqmH;

    @Column(name = "efficiency_mop_sqm_h")
    private Integer efficiencyMopSqmH;

    @Column(name = "efficiency_sweep_scrub_sqm_h")
    private Integer efficiencySweepScrubSqmH;

    @Column(name = "efficiency_vacuum_sqm_h")
    private Integer efficiencyVacuumSqmH;

    // ── Dimension 3 – Coverage capacity: tanks & speed ────────────────────────
    @Column(name = "tank_clean_l", precision = 6, scale = 2)
    private BigDecimal tankCleanL;

    @Column(name = "tank_waste_l", precision = 6, scale = 2)
    private BigDecimal tankWasteL;

    @Column(name = "tank_trash_l", precision = 6, scale = 2)
    private BigDecimal tankTrashL;

    @Column(name = "dust_bag_l", precision = 6, scale = 2)
    private BigDecimal dustBagL;

    @Column(name = "speed_ms", precision = 5, scale = 2)
    private BigDecimal speedMs;

    // ── Battery ───────────────────────────────────────────────────────────────
    @Column(name = "battery_type", length = 20)
    private String batteryType;

    @Column(name = "battery_voltage_v", precision = 6, scale = 1)
    private BigDecimal batteryVoltageV;

    @Column(name = "battery_capacity_ah", precision = 8, scale = 2)
    private BigDecimal batteryCapacityAh;

    @Column(name = "charging_time_hr", precision = 5, scale = 2)
    private BigDecimal chargingTimeHr;

    @Column(name = "battery_work_hr", precision = 5, scale = 2)
    private BigDecimal batteryWorkHr;

    @Column(name = "work_time_sweep_hr", precision = 5, scale = 2)
    private BigDecimal workTimeSweepHr;

    @Column(name = "work_time_scrub_hr", precision = 5, scale = 2)
    private BigDecimal workTimeScrubHr;

    @Column(name = "work_time_sweep_vacuum_hr", precision = 5, scale = 2)
    private BigDecimal workTimeSweepVacuumHr;

    // ── Dimension 2 – Size & access ───────────────────────────────────────────
    @Column(name = "min_passable_width_mm")
    private Integer minPassableWidthMm;

    @Column(name = "min_passable_height_mm")
    private Integer minPassableHeightMm;

    @Column(name = "max_narrow_cross_mm")
    private Integer maxNarrowCrossMm;

    @Column(name = "min_turn_width_mm")
    private Integer minTurnWidthMm;

    @Column(name = "min_edge_from_wall_mm")
    private Integer minEdgeFromWallMm;

    @Column(name = "max_step_height_mm")
    private Integer maxStepHeightMm;

    @Column(name = "slope_angle_deg", precision = 5, scale = 1)
    private BigDecimal slopeAngleDeg;

    // ── Dimension 4 – Floor suitability ───────────────────────────────────────
    @Column(name = "floor_paving_blocks")
    private Boolean floorPavingBlocks;

    @Column(name = "floor_granite")
    private Boolean floorGranite;

    @Column(name = "floor_marble")
    private Boolean floorMarble;

    @Column(name = "floor_terrazzo")
    private Boolean floorTerrazzo;

    @Column(name = "floor_terracotta")
    private Boolean floorTerracotta;

    @Column(name = "floor_ceramic")
    private Boolean floorCeramic;

    @Column(name = "floor_smooth_concrete")
    private Boolean floorSmoothConcrete;

    @Column(name = "floor_coarse_concrete")
    private Boolean floorCoarseConcrete;

    @Column(name = "floor_stamped_concrete")
    private Boolean floorStampedConcrete;

    @Column(name = "floor_asphalt")
    private Boolean floorAsphalt;

    @Column(name = "floor_epoxy")
    private Boolean floorEpoxy;

    @Column(name = "floor_tile")
    private Boolean floorTile;

    @Column(name = "floor_short_carpet")
    private Boolean floorShortCarpet;

    @Column(name = "floor_long_carpet")
    private Boolean floorLongCarpet;

    @Column(name = "floor_spc")
    private Boolean floorSpc;

    @Column(name = "floor_laminate")
    private Boolean floorLaminate;

    @Column(name = "floor_vinyl")
    private Boolean floorVinyl;

    // Floor tile layout sizes
    @Column(name = "layout_2x2")
    private Boolean layout2x2;

    @Column(name = "layout_4x4")
    private Boolean layout4x4;

    @Column(name = "layout_8x8")
    private Boolean layout8x8;

    @Column(name = "layout_10x10")
    private Boolean layout10x10;

    @Column(name = "layout_12x12")
    private Boolean layout12x12;

    @Column(name = "layout_20x20")
    private Boolean layout20x20;

    // ── Dimension 5 – Environment fit ─────────────────────────────────────────
    @Column(name = "is_indoor")
    private Boolean isIndoor;

    @Column(name = "is_outdoor")
    private Boolean isOutdoor;

    @Column(name = "ip_rating", length = 20)
    private String ipRating;

    @Column(name = "hepa")
    private Boolean hepa;

    // ── Dimension 6 – Operational quality ─────────────────────────────────────
    @Column(name = "noise_db", precision = 5, scale = 1)
    private BigDecimal noiseDb;

    @Column(name = "nav_lidar_2d")
    private Boolean navLidar2d;

    @Column(name = "nav_lidar_3d")
    private Boolean navLidar3d;

    @Column(name = "nav_vslam")
    private Boolean navVslam;

    @Column(name = "has_workstation")
    private Boolean hasWorkstation;

    @Column(name = "dock_charge")
    private Boolean dockCharge;

    @Column(name = "manual_charge")
    private Boolean manualCharge;

    @Column(name = "has_spot_ai")
    private Boolean hasSpotAi;

    // ── Timestamps ────────────────────────────────────────────────────────────
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}