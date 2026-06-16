package com.raaspal.robotrecommendation.telemetry.adapters.gausium.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Stores the rotating OAuth access/refresh token pair for a brand's API.
 * One row per brand (currently only "GAUSIUM").
 */
@Entity
@Table(name = "gausium_oauth_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GausiumOAuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String brand;

    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "refresh_token", nullable = false, columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}