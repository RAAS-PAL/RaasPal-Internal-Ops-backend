package com.raaspal.robotrecommendation.partner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An API key a partner uses to call the partner API. Only the SHA-256 hash of
 * the key is stored — the plaintext is shown once at creation and cannot be
 * recovered. {@code keyPrefix} (the key's first characters) enables indexed
 * lookup and safe display. Revoking = {@code isActive=false} + {@code revokedAt};
 * rotation = issue a new row, revoke the old one.
 */
@Entity
@Table(name = "partner_api_keys")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;

    /** SHA-256 of the plaintext key, hex-encoded (64 chars). */
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    /** First characters of the plaintext key, for lookup and display. */
    @Column(name = "key_prefix", nullable = false, length = 12)
    private String keyPrefix;

    /** Free-text note, e.g. "PCS production key". */
    @Column(length = 255)
    private String label;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;
}
