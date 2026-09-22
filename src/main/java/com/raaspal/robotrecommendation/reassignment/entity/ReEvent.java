package com.raaspal.robotrecommendation.reassignment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Something a person did in the RE module - append-only audit. Maps to {@code re_event}. */
@Entity
@Table(name = "re_event")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false, columnDefinition = "TEXT")
    private String entityType;

    @Column(name = "entity_id", nullable = false, columnDefinition = "TEXT")
    private String entityId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String action;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    @Builder.Default
    private String detail = "{}";

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
