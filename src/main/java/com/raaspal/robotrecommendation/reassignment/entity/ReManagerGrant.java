package com.raaspal.robotrecommendation.reassignment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A non-admin user allowed to manage skills and approve assignments. Maps to {@code re_manager_grant}. */
@Entity
@Table(name = "re_manager_grant")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReManagerGrant {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "granted_by", nullable = false, columnDefinition = "TEXT")
    private String grantedBy;

    @Column(name = "granted_at", nullable = false)
    @Builder.Default
    private Instant grantedAt = Instant.now();
}
