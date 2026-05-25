package com.raaspal.robotrecommendation.robot.entity;

import com.raaspal.robotrecommendation.common.enums.BudgetBand;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.enums.TestStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "robots")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Robot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String brand;

    @Column(nullable = false)
    private String model;

    /** Which catalog this robot belongs to. */
    @Enumerated(EnumType.STRING)
    @Column(name = "robot_type", nullable = false, length = 20)
    private RobotType robotType;

    /** Distinguishes "not yet tested" from "verified" data. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "test_status", nullable = false, length = 20)
    private TestStatus testStatus = TestStatus.DRAFT;

    /** Coarse price band used for budget scoring. */
    @Enumerated(EnumType.STRING)
    @Column(name = "price_band", length = 10)
    private BudgetBand priceBand;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "datasheet_url", length = 500)
    private String datasheetUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}