package com.raaspal.robotrecommendation.audit.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable system event log — rows are never updated or deleted.
 * actor_id / actor_email are stored directly (not as FK) so audit records
 * survive even if the user is later deleted.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** UUID of the user who performed the action. Nullable for system events. */
    @Column(name = "actor_id")
    private UUID actorId;

    /** Email snapshot at the time of the action (survives user deletion). */
    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    /** Action performed, e.g. CREATE, UPDATE, DELETE, SUBMIT, GENERATE. */
    @Column(nullable = false, length = 50)
    private String action;

    /** Entity type affected, e.g. "Requirement", "Recommendation". */
    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    /** UUID of the affected entity. */
    @Column(name = "entity_id")
    private UUID entityId;

    /** JSON snapshot of the record before the change. */
    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    /** JSON snapshot of the record after the change. */
    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    /** Free-text context or error message. */
    @Column(columnDefinition = "TEXT")
    private String details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}