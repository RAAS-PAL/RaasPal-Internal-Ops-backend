package com.raaspal.robotrecommendation.partner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A successful OAuth 2.0 token response (RFC 6749 §5.1).
 *
 * <p>Field names are snake_case because the specification says so — clients and
 * off-the-shelf OAuth libraries look for exactly {@code access_token},
 * {@code token_type} and {@code expires_in}. This response deliberately does
 * <em>not</em> use the project's usual {@code ApiResponse} envelope: a partner
 * pointing a standard OAuth client at this endpoint must find the shape the
 * specification promises, not ours.
 */
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn) {

    public static TokenResponse bearer(String accessToken, long expiresInSeconds) {
        return new TokenResponse(accessToken, "Bearer", expiresInSeconds);
    }
}
