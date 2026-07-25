package com.raaspal.robotrecommendation.partner.dto;

import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService.GeneratedKey;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The response to minting a key. The plaintext {@code apiKey} is returned
 * <strong>once</strong> and is never recoverable afterwards — the admin must
 * copy it now. Later listings only ever show {@code keyPrefix}.
 */
public record CreatedApiKeyResponse(
        UUID id,
        String apiKey,
        String keyPrefix,
        String label,
        /** When the key stops working; {@code null} = never expires. */
        LocalDateTime expiresAt,
        String warning) {

    public static CreatedApiKeyResponse of(GeneratedKey key) {
        return new CreatedApiKeyResponse(
                key.id(),
                key.apiKey(),
                key.keyPrefix(),
                key.label(),
                key.expiresAt(),
                "Copy this key now — it is shown only once and cannot be recovered.");
    }
}
