package com.raaspal.robotrecommendation.telemetry.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * What the customer success team did about one robot that logged nothing in one
 * month. Maps to {@code zero_data_followups} (V42).
 *
 * <p>The list of such robots is computed on request; this is the overlay that turns it
 * into a worklist. One row per robot per month, created the first time somebody
 * touches the entry, updated in place after that.
 */
@Entity
@Table(name = "zero_data_followups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZeroDataFollowup {

    public enum Status { TO_CONTACT, CONTACTED, RESOLVED }

    /** What it turned out to be. Only meaningful once {@link Status#RESOLVED}. */
    public enum Outcome { ROBOT_OFFLINE, IN_STORAGE, CONTRACT_ENDED, REGISTRATION_ERROR, SYNC_PROBLEM, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "robot_unit_id", nullable = false)
    private UUID robotUnitId;

    @Column(name = "report_month", nullable = false, length = 7)
    private String reportMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private Outcome outcome;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "updated_by", columnDefinition = "TEXT")
    private String updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
