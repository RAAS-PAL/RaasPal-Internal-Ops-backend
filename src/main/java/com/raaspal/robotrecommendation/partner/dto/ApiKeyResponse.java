package com.raaspal.robotrecommendation.partner.dto;

import com.raaspal.robotrecommendation.partner.entity.PartnerApiKey;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Non-secret metadata for one of a partner's API keys. The key hash and
 * plaintext are never exposed — only the display {@code keyPrefix}.
 */
public record ApiKeyResponse(
        UUID id,
        String keyPrefix,
        String label,
        /** Live: active, not revoked, and not expired. */
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt,
        LocalDateTime revokedAt,
        /** When the key stops working; {@code null} = never expires. */
        LocalDateTime expiresAt,
        boolean expired,
        /** A rotation nudge: still usable, but expiring within the next week. */
        boolean expiringSoon) {

    /** How close to expiry a key must be before the UI nudges for rotation. */
    private static final Duration ROTATION_WARNING = Duration.ofDays(7);

    public static ApiKeyResponse of(PartnerApiKey key) {
        boolean expired = key.isExpired();
        return new ApiKeyResponse(
                key.getId(),
                key.getKeyPrefix(),
                key.getLabel(),
                key.isUsable(),
                key.getCreatedAt(),
                key.getLastUsedAt(),
                key.getRevokedAt(),
                key.getExpiresAt(),
                expired,
                isExpiringSoon(key, expired));
    }

    private static boolean isExpiringSoon(PartnerApiKey key, boolean expired) {
        if (expired || key.getExpiresAt() == null || !key.isUsable()) {
            return false;
        }
        return key.getExpiresAt().isBefore(LocalDateTime.now().plus(ROTATION_WARNING));
    }
}
