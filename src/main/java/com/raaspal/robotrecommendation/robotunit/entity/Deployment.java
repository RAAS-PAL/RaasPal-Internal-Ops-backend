package com.raaspal.robotrecommendation.robotunit.entity;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /** How often this robot's report is sent on a schedule. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "report_cadence", nullable = false, length = 20)
    private ReportCadence reportCadence = ReportCadence.MONTHLY;

    @Column(name = "deployed_at", nullable = false)
    private LocalDateTime deployedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}