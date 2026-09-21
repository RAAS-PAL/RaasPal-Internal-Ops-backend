package com.raaspal.robotrecommendation.telemetry.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One robot fault occurrence, from first seen to cleared. Maps to {@code robot_fault_event} (V52).
 *
 * <p>Written by the fleet poller only on a change: a row opens when a fault appears and
 * closes when it goes away, so an active fault costs nothing per poll.
 */
@Entity
@Table(name = "robot_fault_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RobotFaultEvent {

    public enum Kind { ERROR, EMERGENCY_STOP, OFFLINE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 20)
    private String brand;

    @Column(name = "robot_id", nullable = false, columnDefinition = "TEXT")
    private String robotId;

    @Column(name = "business_id", columnDefinition = "TEXT")
    private String businessId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    /** Set for {@link Kind#ERROR} only. */
    @Column(name = "error_code")
    private Integer errorCode;

    @Column(name = "error_level")
    private Integer errorLevel;

    /** The robot's own text, e.g. "Wheel is major slipping". */
    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    /** Null while the fault is still active. */
    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
