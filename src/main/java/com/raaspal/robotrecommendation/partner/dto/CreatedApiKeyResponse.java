package com.raaspal.robotrecommendation.partner.dto;

import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService.GeneratedKey;

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
        String warning) {

    public static CreatedApiKeyResponse of(GeneratedKey key) {
        return new CreatedApiKeyResponse(
                key.id(),
                key.apiKey(),
                key.keyPrefix(),
                key.label(),
                "Copy this key now — it is shown only once and cannot be recovered.");
    }
}
