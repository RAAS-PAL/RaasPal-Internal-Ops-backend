package com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto;

/**
 * A freshly minted AutoXing access token and its lifetime. Tokens are short-lived
 * (typically 600 seconds) and non-refreshable — the client re-signs and requests a
 * new one when the cached one is near expiry.
 */
public record AutoxingToken(String token, long expiresInSeconds) {
}
