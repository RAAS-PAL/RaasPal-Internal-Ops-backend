package com.raaspal.robotrecommendation.casereport.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One report. Maps to {@code case_report_definition} (V38).
 *
 * <p>"All Pending Cases", "AOT", "MK". Adding a fourth is an INSERT, not a deploy.
 *
 * <p>{@link #generatorKey} names the class that builds the rows, the same wiring
 * {@code TelemetryAdapterRegistry} uses to map "gausium" to its adapter. Column layout and
 * filter logic live in that class rather than here, so a change to AOT cannot affect MK.
 *
 * <p>The SLA days are here, not in code, because customers may sign different terms and
 * none of them should need a deploy to correct.
 */
@Entity
@Table(name = "case_report_definition")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseReportDefinition {

    /** The MK sheet: MK, Yayoi and Bonus Suki delivery — one customer, three brands. */
    public static final String MK_PENDING = "MK_PENDING";
    public static final String CLEANING_PENDING = "CLEANING_PENDING";
    public static final String MAKRO_PENDING = "MAKRO_PENDING";
    /** The airports' sheet: spare-part turnaround rather than an SLA. */
    public static final String AOTGA_PENDING = "AOTGA_PENDING";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "generator_key", nullable = false, length = 50)
    private String generatorKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CaseSource source = CaseSource.MONDAY;

    @Column(name = "source_board_id", nullable = false, columnDefinition = "TEXT")
    private String sourceBoardId;

    @Column(name = "source_group_id", nullable = false, columnDefinition = "TEXT")
    private String sourceGroupId;

    /**
     * AUTO sends without asking; MANUAL parks the run at AWAITING_APPROVAL.
     *
     * <p>Defaults to MANUAL, and stays there until the generated report has been checked
     * against the hand-built one often enough to be trusted.
     */
    @Column(name = "delivery_mode", nullable = false, length = 10)
    @Builder.Default
    private String deliveryMode = "MANUAL";

    @Column(name = "schedule_cron", length = 50)
    private String scheduleCron;

    @Column(name = "schedule_zone", nullable = false, length = 50)
    @Builder.Default
    private String scheduleZone = "Asia/Bangkok";

    /** 3 days inside the six greater-Bangkok provinces. */
    @Column(name = "sla_days_metro", nullable = false)
    @Builder.Default
    private Integer slaDaysMetro = 3;

    /**
     * 5 days elsewhere.
     *
     * <p>Equal to {@link #slaDaysMetro} on the cleaning reports, and the calculator then
     * skips the province entirely — which is what lets a cleaning ticket, whose board has
     * no Province column, still get a verdict.
     */
    @Column(name = "sla_days_upcountry", nullable = false)
    @Builder.Default
    private Integer slaDaysUpcountry = 5;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
