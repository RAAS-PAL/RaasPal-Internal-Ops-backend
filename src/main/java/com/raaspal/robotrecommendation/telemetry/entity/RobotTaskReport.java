package com.raaspal.robotrecommendation.telemetry.entity;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single synced robot cleaning task report. One table is shared across all
 * brands; brand-specific columns are nullable.
 */
@Entity
@Table(name = "robot_task_reports")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RobotTaskReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "external_task_id", nullable = false, unique = true)
    private String externalTaskId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "robot_unit_id", nullable = false)
    private RobotUnit robotUnit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_profile_id", nullable = false)
    private CustomerProfile customerProfile;

    @Column(nullable = false, length = 50)
    private String brand;

    // ── Common fields ──
    @Column(name = "cleaning_plan")
    private String cleaningPlan;

    @Column(name = "map_name", columnDefinition = "TEXT")
    private String mapName;

    @Column(name = "task_completion_pct", precision = 5, scale = 2)
    private BigDecimal taskCompletionPct;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "work_efficiency_sqm_h", precision = 10, scale = 2)
    private BigDecimal workEfficiencySqmH;

    @Column(name = "working_time_seconds")
    private Integer workingTimeSeconds;

    @Column(name = "cleaning_area_sqm", precision = 10, scale = 2)
    private BigDecimal cleaningAreaSqm;

    @Column(name = "planned_area_sqm", precision = 10, scale = 2)
    private BigDecimal plannedAreaSqm;

    @Column(name = "start_battery_pct")
    private Integer startBatteryPct;

    @Column(name = "end_battery_pct")
    private Integer endBatteryPct;

    @Column(name = "water_consumption_l", precision = 8, scale = 2)
    private BigDecimal waterConsumptionL;

    // ── Gausium-specific (nullable) ──
    @Column(name = "brush_residual_pct", precision = 5, scale = 2)
    private BigDecimal brushResidualPct;

    @Column(name = "filter_residual_pct", precision = 5, scale = 2)
    private BigDecimal filterResidualPct;

    @Column(name = "suction_blade_residual_pct", precision = 5, scale = 2)
    private BigDecimal suctionBladeResidualPct;

    @Column(name = "planned_polishing_area_sqm", precision = 10, scale = 2)
    private BigDecimal plannedPolishingAreaSqm;

    @Column(name = "actual_polishing_area_sqm", precision = 10, scale = 2)
    private BigDecimal actualPolishingAreaSqm;

    @Column(name = "cleaning_mode", length = 100)
    private String cleaningMode;

    @Column(name = "task_report_png_uri", length = 500)
    private String taskReportPngUri;

    @Column(name = "task_end_status")
    private Integer taskEndStatus;

    @Column(name = "operator", length = 255)
    private String operator;

    // ── Sync metadata ──
    @Column(name = "report_month", nullable = false, length = 7)
    private String reportMonth;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}