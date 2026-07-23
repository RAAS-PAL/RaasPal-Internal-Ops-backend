package com.raaspal.robotrecommendation.telemetry.adapters.autoxing;

import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Manages the short-lived AutoXing access token. Tokens live ~600 seconds and are
 * NOT refreshable, so — unlike the DB-backed Gausium token — this keeps only a small
 * in-memory copy and re-signs for a fresh token when the current one is near expiry
 * (or on demand after an auth failure). "Refresh per run, not cache long-term."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoxingAuthService {

    /** Re-fetch this long before the token actually expires to avoid using a stale one mid-run. */
    private static final Duration EXPIRY_BUFFER = Duration.ofSeconds(60);

    /**
     * Never trust the token for longer than this. The live endpoint returns an
     * {@code expireTime} whose unit is ambiguous (600 in the docs vs 603800 live),
     * so we cap the cache well under a documented ~10-minute lifetime and re-sign —
     * matching "refresh per run, not cache long-term". A stale-token 401 is also
     * caught and retried by the report service regardless.
     */
    private static final long MAX_TTL_SECONDS = 300;

    private final AutoxingApiClient apiClient;

    private volatile String cachedToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    /** Returns a valid token, fetching a fresh one if none is cached or it is near expiry. */
    public synchronized String getValidToken() {
        if (cachedToken != null && expiresAt.isAfter(Instant.now().plus(EXPIRY_BUFFER))) {
            return cachedToken;
        }
        return refresh();
    }

    /** Forces a fresh token — used after an auth failure to retry a call once. */
    public synchronized String refresh() {
        AutoxingToken token = apiClient.fetchToken();
        cachedToken = token.token();
        long ttl = Math.min(Math.max(token.expiresInSeconds(), 1), MAX_TTL_SECONDS);
        expiresAt = Instant.now().plusSeconds(ttl);
        log.debug("Fetched new AutoXing token, caching for {}s", ttl);
        return cachedToken;
    }
}
