package com.raaspal.robotrecommendation.partner.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and validates the short-lived bearer tokens returned by the partner
 * OAuth token endpoint.
 *
 * <p><strong>Why this is not {@code JwtUtils}.</strong> The staff
 * {@code JwtUtils} signs with {@code app.jwt.secret} and validates only the
 * signature and expiry — it carries no audience or type claim. Minting partner
 * tokens with it would produce tokens that also satisfy the staff filter's
 * validation; they would fail afterwards only because the subject happens not to
 * resolve to a user account. That is safety by coincidence.
 *
 * <p>Using a <strong>separate signing secret</strong> makes the two token
 * families cryptographically incompatible: a staff token cannot be parsed here,
 * and a partner token cannot be parsed there. No claim check can be forgotten
 * later, because the signature itself is the boundary.
 */
@Slf4j
@Component
public class PartnerTokenService {

    /** Marks the token's purpose, so its intent is explicit even within this family. */
    private static final String TOKEN_TYPE = "partner";
    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_API_KEY_ID = "apiKeyId";
    private static final String CLAIM_PARTNER_NAME = "partnerName";

    private final SecretKey signingKey;
    private final long expirationMs;

    public PartnerTokenService(
            @Value("${app.partner.jwt.secret}") String secret,
            @Value("${app.partner.jwt.expiration-ms:3600000}") long expirationMs) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /** Seconds until a freshly issued token expires — the OAuth {@code expires_in}. */
    public long expiresInSeconds() {
        return expirationMs / 1000;
    }

    /** Mints a bearer for an authenticated partner credential. */
    public String issue(UUID partnerId, String partnerName, UUID apiKeyId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(partnerId.toString())
                .claim(CLAIM_TYPE, TOKEN_TYPE)
                .claim(CLAIM_API_KEY_ID, apiKeyId.toString())
                .claim(CLAIM_PARTNER_NAME, partnerName)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validates a bearer and returns who it belongs to, or empty if it is
     * malformed, expired, signed with another key, or not a partner token.
     */
    public Optional<PartnerPrincipal> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Defence in depth: the separate secret already excludes staff tokens,
            // but an explicit type check keeps the contract visible.
            if (!TOKEN_TYPE.equals(claims.get(CLAIM_TYPE, String.class))) {
                return Optional.empty();
            }

            return Optional.of(new PartnerPrincipal(
                    UUID.fromString(claims.getSubject()),
                    claims.get(CLAIM_PARTNER_NAME, String.class),
                    UUID.fromString(claims.get(CLAIM_API_KEY_ID, String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            // Includes expiry, bad signature, and anything unparseable. Never
            // throw: a bad token simply means "not authenticated".
            log.debug("Rejected partner token: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
