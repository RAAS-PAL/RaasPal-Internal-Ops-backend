package com.raaspal.robotrecommendation.robot.entity;

import com.raaspal.robotrecommendation.common.enums.Environment;
import com.raaspal.robotrecommendation.common.enums.PricingType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

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

    @Column(name = "cleaning_width_mm")
    private Integer cleaningWidthMm;

    // ── Performance ───────────────────────────────────────────────────────────
    @Column(name = "cleaning_efficiency_sqm_h")
    private Integer cleaningEfficiencySqmH;

    @Column(name = "speed_ms", precision = 5, scale = 2)
    private BigDecimal speedMs;

    @Column(name = "noise_db", precision = 5, scale = 1)
    private BigDecimal noiseDb;

    // ── Battery ───────────────────────────────────────────────────────────────
    @Column(name = "battery_work_time_h", precision = 5, scale = 2)
    private BigDecimal batteryWorkTimeH;

    @Column(name = "charging_time_h", precision = 5, scale = 2)
    private BigDecimal chargingTimeH;

    // ── Navigation & Environment ──────────────────────────────────────────────
    @Column(name = "navigation_type", length = 20)
    private String navigationType;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Environment environment;

    @Column(name = "ip_rating", length = 20)
    private String ipRating;

    @Column(name = "min_passable_width_mm")
    private Integer minPassableWidthMm;

    // ── Pricing ───────────────────────────────────────────────────────────────
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_type", nullable = false, length = 10)
    private PricingType pricingType = PricingType.BOTH;

    @Column(name = "sale_price_thb", precision = 15, scale = 2)
    private BigDecimal salePriceThb;

    @Column(name = "rental_price_thb", precision = 15, scale = 2)
    private BigDecimal rentalPriceThb;

    // ── Catch-all ─────────────────────────────────────────────────────────────
    @Column(name = "additional_specs", columnDefinition = "TEXT")
    private String additionalSpecs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}