package com.raaspal.robotrecommendation.mkstock.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** The PIN MK staff enter to view stock, BCrypt-hashed. Maps to {@code mk_access_pin} (V57). */
@Entity
@Table(name = "mk_access_pin")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MkAccessPin {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "pin_hash", nullable = false, columnDefinition = "TEXT")
    private String pinHash;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_by", nullable = false, columnDefinition = "TEXT")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;
}
