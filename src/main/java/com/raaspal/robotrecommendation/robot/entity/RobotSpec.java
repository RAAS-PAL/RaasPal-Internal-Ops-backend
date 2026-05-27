package com.raaspal.robotrecommendation.robot.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Cleaning-robot specifications parsed from the real datasheet CSV.
 *
 * All specification columns are nullable: a blank cell in the datasheet becomes
 * NULL here, never zero. Field names intentionally follow the CSV topics so the
 * import mapping stays direct and auditable.
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

    // Dimension (mm)
    @Column(name = "length_mm")
    private Integer lengthMm;

    @Column(name = "width_mm")
    private Integer widthMm;

    @Column(name = "height_mm")
    private Integer heightMm;

    @Column(name = "robot_weight_kg", precision = 8, scale = 2)
    private BigDecimal robotWeightKg;

    @Column(name = "width_cleaning_mm")
    private Integer widthCleaningMm;

    @Column(name = "brush_pressure_kg", precision = 6, scale = 2)
    private BigDecimal brushPressureKg;

    @Column(name = "vacuum_pressure_kpa", precision = 6, scale = 2)
    private BigDecimal vacuumPressureKpa;

    @Column(name = "speed_ms", precision = 5, scale = 2)
    private BigDecimal speedMs;

    @Column(name = "noise_level_db", precision = 5, scale = 1)
    private BigDecimal noiseLevelDb;

    @Column(name = "work_station")
    private Boolean workStation;

    @Column(name = "dock_charge")
    private Boolean dockCharge;

    @Column(name = "manual_charge")
    private Boolean manualCharge;

    // Cleaning Efficiency (sqm/h)
    @Column(name = "cleaning_efficiency_sweep_sqm_h")
    private Integer cleaningEfficiencySweepSqmH;

    @Column(name = "cleaning_efficiency_scrub_sqm_h")
    private Integer cleaningEfficiencyScrubSqmH;

    @Column(name = "cleaning_efficiency_mop_sqm_h")
    private Integer cleaningEfficiencyMopSqmH;

    @Column(name = "cleaning_efficiency_sweep_scrub_sqm_h")
    private Integer cleaningEfficiencySweepScrubSqmH;

    @Column(name = "cleaning_efficiency_vacuum_sqm_h")
    private Integer cleaningEfficiencyVacuumSqmH;

    // Tank capacity (L.)
    @Column(name = "tank_capacity_clean_l", precision = 6, scale = 2)
    private BigDecimal tankCapacityCleanL;

    @Column(name = "tank_capacity_waste_l", precision = 6, scale = 2)
    private BigDecimal tankCapacityWasteL;

    @Column(name = "tank_capacity_trash_l", precision = 6, scale = 2)
    private BigDecimal tankCapacityTrashL;

    @Column(name = "tank_capacity_dust_bag_l", precision = 6, scale = 2)
    private BigDecimal tankCapacityDustBagL;

    // Cleaning Function
    @Column(name = "cleaning_function_sweep_no_vacuum")
    private Boolean cleaningFunctionSweepNoVacuum;

    @Column(name = "cleaning_function_sweep_vacuum")
    private Boolean cleaningFunctionSweepVacuum;

    @Column(name = "cleaning_function_mop_dry")
    private Boolean cleaningFunctionMopDry;

    @Column(name = "cleaning_function_mop_wet")
    private Boolean cleaningFunctionMopWet;

    @Column(name = "cleaning_function_scrub_brush_roller")
    private Boolean cleaningFunctionScrubBrushRoller;

    @Column(name = "cleaning_function_scrub_brush_disc")
    private Boolean cleaningFunctionScrubBrushDisc;

    // Navigation
    @Column(name = "navigation_lidar_2d")
    private Boolean navigationLidar2d;

    @Column(name = "navigation_lidar_3d")
    private Boolean navigationLidar3d;

    @Column(name = "navigation_camera_vslam")
    private Boolean navigationCameraVslam;

    // Battery
    @Column(name = "battery_type", length = 50)
    private String batteryType;

    @Column(name = "battery_voltage_v", precision = 6, scale = 1)
    private BigDecimal batteryVoltageV;

    @Column(name = "battery_capacity_ah", precision = 8, scale = 2)
    private BigDecimal batteryCapacityAh;

    @Column(name = "battery_charging_time_hr", precision = 5, scale = 2)
    private BigDecimal batteryChargingTimeHr;

    @Column(name = "battery_work_time_sweep_hr", precision = 5, scale = 2)
    private BigDecimal batteryWorkTimeSweepHr;

    @Column(name = "battery_work_time_scrub_hr", precision = 5, scale = 2)
    private BigDecimal batteryWorkTimeScrubHr;

    @Column(name = "battery_work_time_sweep_vacuum_hr", precision = 5, scale = 2)
    private BigDecimal batteryWorkTimeSweepVacuumHr;

    // Access and movement
    @Column(name = "minimum_passable_width_mm")
    private Integer minimumPassableWidthMm;

    @Column(name = "minimum_passable_height_mm")
    private Integer minimumPassableHeightMm;

    @Column(name = "maximum_narrow_cross_mm")
    private Integer maximumNarrowCrossMm;

    @Column(name = "minimum_turn_width_mm")
    private Integer minimumTurnWidthMm;

    @Column(name = "minimum_edge_from_wall_mm")
    private Integer minimumEdgeFromWallMm;

    @Column(name = "maximum_step_height_mm")
    private Integer maximumStepHeightMm;

    @Column(name = "slope_angle_deg", precision = 5, scale = 1)
    private BigDecimal slopeAngleDeg;

    @Column(name = "spot_ai")
    private Boolean spotAi;

    @Column(name = "outdoor_indoor", length = 50)
    private String outdoorIndoor;

    @Column(name = "ip_rating", length = 20)
    private String ipRating;

    @Column(name = "hepa")
    private Boolean hepa;

    // Floor Type
    @Column(name = "floor_type_paving_blocks")
    private Boolean floorTypePavingBlocks;

    @Column(name = "floor_type_granite")
    private Boolean floorTypeGranite;

    @Column(name = "floor_type_marble")
    private Boolean floorTypeMarble;

    @Column(name = "floor_type_terrazzo")
    private Boolean floorTypeTerrazzo;

    @Column(name = "floor_type_terracotta")
    private Boolean floorTypeTerracotta;

    @Column(name = "floor_type_ceramic")
    private Boolean floorTypeCeramic;

    @Column(name = "floor_type_smooth_concrete")
    private Boolean floorTypeSmoothConcrete;

    @Column(name = "floor_type_coarse_concrete")
    private Boolean floorTypeCoarseConcrete;

    @Column(name = "floor_type_stamped_concrete")
    private Boolean floorTypeStampedConcrete;

    @Column(name = "floor_type_asphalt")
    private Boolean floorTypeAsphalt;

    @Column(name = "floor_type_epoxy")
    private Boolean floorTypeEpoxy;

    @Column(name = "floor_type_tile")
    private Boolean floorTypeTile;

    @Column(name = "floor_type_short_carpet")
    private Boolean floorTypeShortCarpet;

    @Column(name = "floor_type_long_carpet")
    private Boolean floorTypeLongCarpet;

    @Column(name = "floor_type_spc")
    private Boolean floorTypeSpc;

    @Column(name = "floor_type_laminate")
    private Boolean floorTypeLaminate;

    @Column(name = "floor_type_vinyl")
    private Boolean floorTypeVinyl;

    // Floor Layout Method
    @Column(name = "floor_layout_2x2")
    private Boolean floorLayout2x2;

    @Column(name = "floor_layout_4x4")
    private Boolean floorLayout4x4;

    @Column(name = "floor_layout_8x8")
    private Boolean floorLayout8x8;

    @Column(name = "floor_layout_10x10")
    private Boolean floorLayout10x10;

    @Column(name = "floor_layout_12x12")
    private Boolean floorLayout12x12;

    @Column(name = "floor_layout_20x20")
    private Boolean floorLayout20x20;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
