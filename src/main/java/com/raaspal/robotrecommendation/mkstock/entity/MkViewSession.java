package com.raaspal.robotrecommendation.mkstock.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A view-only session for MK staff, handed out for a correct PIN. Only the SHA-256 of the
 * token is kept. Maps to {@code mk_view_session} (V57).
 */
@Entity
@Table(name = "mk_view_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MkViewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true, columnDefinition = "TEXT")
    private String tokenHash;

    @Column(name = "pin_id", nullable = false)
    private UUID pinId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
