package com.raaspal.robotrecommendation.telemetry.adapters.gausium;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.telemetry.adapters.gausium.dto.GausiumOAuthTokenResponse;
import com.raaspal.robotrecommendation.telemetry.adapters.gausium.dto.GausiumTaskReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client for Gausium's Open API (OAuth token endpoint + V2 List
 * Robot Task Reports). Stateless - does not cache or persist tokens; see
 * {@link GausiumOAuthService} for token lifecycle management.
 */
@Slf4j
@Component
public class GausiumApiClient {

    private static final String OAUTH_TOKEN_PATH = "/gas/api/v1alpha1/oauth/token";
    private static final String OPEN_ACCESS_TOKEN_GRANT_TYPE =
            "urn:gaussian:params:oauth:grant-type:open-access-token";
    private static final String TASK_REPORTS_PATH = "/openapi/v2alpha1/robots/{robotSerialNumber}/taskReports";
    private static final DateTimeFormatter QUERY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int PAGE_SIZE = 200;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;
    private final String openAccessKey;

    public GausiumApiClient(
            @Value("${app.gausium.api.base-url:https://openapi.gs-robot.com}") String baseUrl,
            @Value("${app.gausium.api.client-id:}") String clientId,
            @Value("${app.gausium.api.client-secret:}") String clientSecret,
            @Value("${app.gausium.api.open-access-key:}") String openAccessKey,
            @Value("${app.gausium.api.connect-timeout-seconds:15}") int connectTimeoutSeconds,
            @Value("${app.gausium.api.read-timeout-seconds:90}") int readTimeoutSeconds,
            ObjectMapper objectMapper) {
        // Timeouts are not optional here. The fleet sync runs on a single thread, so a
        // Gausium response that never arrives parks every remaining robot behind it --
        // no error, no progress, and nothing to see but a counter that stopped. With a
        // read timeout the call fails, the per-robot catch isolates it, and the run
        // moves on. A page of 200 task reports can legitimately take a while, hence 90s
        // rather than something tighter.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.openAccessKey = openAccessKey;
        this.objectMapper = objectMapper;
    }

    /** Whether the credentials needed to call the Gausium API are configured. */
    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !openAccessKey.isBlank();
    }

    /** Requests a brand-new access/refresh token pair using the configured open access key. */
    public GausiumOAuthTokenResponse fetchAccessToken() {
        Map<String, String> body = Map.of(
                "grant_type", OPEN_ACCESS_TOKEN_GRANT_TYPE,
                "client_id", clientId,
                "client_secret", clientSecret,
                "open_access_key", openAccessKey
        );
        return requestToken(body, "fetch access token");
    }

    /** Exchanges a refresh token for a new access/refresh token pair. */
    public GausiumOAuthTokenResponse refreshAccessToken(String refreshToken) {
        Map<String, String> body = Map.of(
                "grant_type", "refresh_token",
                "refresh_token", refreshToken
        );
        return requestToken(body, "refresh access token");
    }

    private GausiumOAuthTokenResponse requestToken(Map<String, String> body, String action) {
        try {
            return restClient.post()
                    .uri(OAUTH_TOKEN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(GausiumOAuthTokenResponse.class);
        } catch (RestClientResponseException e) {
            throw new GausiumApiException(
                    "Failed to " + action + ": " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new GausiumApiException("Failed to " + action + ": " + e.getMessage(), e);
        }
    }

    /**
     * Fetches all task reports for a robot whose start time falls within
     * {@code [from, to]} (inclusive), paging through results as needed.
     */
    public List<GausiumTaskReport> fetchTaskReports(String robotSerialNumber, LocalDate from, LocalDate to, String accessToken) {
        String startTimeMin = from.atStartOfDay().format(QUERY_TIME_FORMAT);
        String startTimeMax = to.atTime(23, 59, 59).format(QUERY_TIME_FORMAT);

        List<GausiumTaskReport> allReports = new ArrayList<>();
        int page = 1;
        int total = Integer.MAX_VALUE;

        while ((page - 1) * PAGE_SIZE < total) {
            JsonNode response = fetchTaskReportsPage(robotSerialNumber, startTimeMin, startTimeMax, page, accessToken);
            List<GausiumTaskReport> pageReports = objectMapper.convertValue(
                    response.path("robotTaskReports"), new TypeReference<List<GausiumTaskReport>>() {
                    });
            allReports.addAll(pageReports);
            total = response.path("total").asInt(allReports.size());
            if (pageReports.isEmpty()) {
                break;
            }
            page++;
        }
        return allReports;
    }

    private JsonNode fetchTaskReportsPage(String robotSerialNumber, String startTimeMin, String startTimeMax, int page, String accessToken) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(TASK_REPORTS_PATH)
                            .queryParam("page", page)
                            .queryParam("pageSize", PAGE_SIZE)
                            .queryParam("startTimeMin", startTimeMin)
                            .queryParam("startTimeMax", startTimeMax)
                            .build(robotSerialNumber))
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new GausiumApiException(
                    "Failed to fetch task reports for robot " + robotSerialNumber + ": "
                            + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new GausiumApiException(
                    "Failed to fetch task reports for robot " + robotSerialNumber + ": " + e.getMessage(), e);
        }
    }
}
