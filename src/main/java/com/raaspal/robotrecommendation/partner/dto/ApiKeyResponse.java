package com.raaspal.robotrecommendation.partner.dto;

import com.raaspal.robotrecommendation.partner.entity.PartnerApiKey;

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
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt,
        LocalDateTime revokedAt) {

    public static ApiKeyResponse of(PartnerApiKey key) {
        return new ApiKeyResponse(
                key.getId(),
                key.getKeyPrefix(),
                key.getLabel(),
                Boolean.TRUE.equals(key.getIsActive()) && key.getRevokedAt() == null,
                key.getCreatedAt(),
                key.getLastUsedAt(),
                key.getRevokedAt());
    }
}
