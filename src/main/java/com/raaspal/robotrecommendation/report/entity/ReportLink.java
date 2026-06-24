package com.raaspal.robotrecommendation.report.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A shareable, public report link: an unguessable {@code token} that maps to one
 * robot ({@code serialNumber}) and {@code reportMonth}. The public report page
 * and the customer's monthly email both use {@code /report/{token}}.
 */
@Entity
@Table(name = "report_links")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "serial_number", nullable = false, length = 100)
    private String serialNumber;

    @Column(name = "report_month", nullable = false, length = 7)
    private String reportMonth;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
