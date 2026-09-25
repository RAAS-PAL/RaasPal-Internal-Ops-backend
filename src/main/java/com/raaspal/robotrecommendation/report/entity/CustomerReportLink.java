package com.raaspal.robotrecommendation.report.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A shareable, public report link covering ALL of a customer's robots for one
 * month. The monthly email links here (not to a per-robot {@link ReportLink}),
 * so the customer gets one URL showing every robot's report.
 */
@Entity
@Table(name = "customer_report_links")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerReportLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "customer_profile_id", nullable = false)
    private UUID customerProfileId;

    @Column(name = "report_month", nullable = false, length = 8)
    private String reportMonth;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
