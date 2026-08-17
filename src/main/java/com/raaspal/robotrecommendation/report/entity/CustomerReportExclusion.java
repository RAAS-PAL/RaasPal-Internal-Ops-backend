package com.raaspal.robotrecommendation.report.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One robot held back from a customer's combined report for one month.
 * <p>
 * Presence of a row means "skip this robot"; deleting it puts the robot straight
 * back into the bundle. Nothing about the robot's telemetry changes either way —
 * this only filters which pages {@link com.raaspal.robotrecommendation.report.service.CustomerReportBundleService}
 * renders.
 * <p>
 * Deliberately keyed by month as well as robot: a robot that was offline in July
 * is usually back in service in August, and an exclusion that quietly carried
 * forward would drop it from every future report with nobody noticing.
 */
@Entity
@Table(
        name = "customer_report_exclusions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_customer_report_exclusion",
                columnNames = {"customer_profile_id", "report_month", "robot_unit_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerReportExclusion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_profile_id", nullable = false)
    private UUID customerProfileId;

    /** "yyyy-MM". */
    @Column(name = "report_month", nullable = false, length = 7)
    private String reportMonth;

    @Column(name = "robot_unit_id", nullable = false)
    private UUID robotUnitId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
