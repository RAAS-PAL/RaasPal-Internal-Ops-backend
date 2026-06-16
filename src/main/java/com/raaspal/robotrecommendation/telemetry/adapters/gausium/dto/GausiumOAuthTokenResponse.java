package com.raaspal.robotrecommendation.telemetry.adapters.gausium.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from Gausium's Get/Refresh OAuth Token endpoints.
 * {@code expiresIn} is documented as an absolute epoch-millisecond
 * timestamp, not a duration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GausiumOAuthTokenResponse(
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("expires_in") Long expiresIn
) {
}