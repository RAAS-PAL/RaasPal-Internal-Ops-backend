package com.raaspal.robotrecommendation.telemetry.adapters.gausium;

import com.raaspal.robotrecommendation.telemetry.adapters.gausium.dto.GausiumOAuthTokenResponse;
import com.raaspal.robotrecommendation.telemetry.adapters.gausium.entity.GausiumOAuthToken;
import com.raaspal.robotrecommendation.telemetry.adapters.gausium.repository.GausiumOAuthTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Manages the lifecycle of the Gausium OAuth access token, persisting it in
 * {@link GausiumOAuthToken} so it survives application restarts and is
 * shared across all robot units.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GausiumOAuthService {

    private static final String BRAND = "GAUSIUM";
    private static final Duration EXPIRY_BUFFER = Duration.ofMinutes(5);

    private final GausiumApiClient apiClient;
    private final GausiumOAuthTokenRepository tokenRepository;

    /** Returns a valid access token, fetching or refreshing it if needed. */
    @Transactional
    public String getValidAccessToken() {
        return tokenRepository.findByBrand(BRAND)
                .map(this::refreshIfNeeded)
                .orElseGet(this::fetchAndPersistNewToken);
    }

    private String refreshIfNeeded(GausiumOAuthToken token) {
        if (token.getExpiresAt().isAfter(Instant.now().plus(EXPIRY_BUFFER))) {
            return token.getAccessToken();
        }
        try {
            GausiumOAuthTokenResponse response = apiClient.refreshAccessToken(token.getRefreshToken());
            return persist(token, response);
        } catch (GausiumApiException e) {
            log.warn("Gausium token refresh failed, requesting a new token instead: {}", e.getMessage());
            return persist(token, apiClient.fetchAccessToken());
        }
    }

    private String fetchAndPersistNewToken() {
        GausiumOAuthToken token = GausiumOAuthToken.builder().brand(BRAND).build();
        return persist(token, apiClient.fetchAccessToken());
    }

    private String persist(GausiumOAuthToken token, GausiumOAuthTokenResponse response) {
        token.setAccessToken(response.accessToken());
        token.setRefreshToken(response.refreshToken());
        token.setExpiresAt(Instant.ofEpochMilli(response.expiresIn()));
        tokenRepository.save(token);
        return token.getAccessToken();
    }
}
