package com.raaspal.robotrecommendation.telemetry.adapters.autoxing;

import com.fasterxml.jackson.databind.JsonNode;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client for the AutoXing Cloud Platform API. Stateless — does not cache
 * tokens (see {@link AutoxingAuthService}). Mirrors the conventions of the Gausium
 * client: a per-client {@link RestClient}, credentials injected via {@code @Value},
 * an {@link #isConfigured()} guard, and errors wrapped in {@link AutoxingApiException}.
 *
 * <p>AutoXing returns a {@code {status, message, data}} envelope; a non-200 {@code status}
 * (notably 400 "Authentication failed") is surfaced as an {@link AutoxingApiException}.
 */
@Slf4j
@Component
public class AutoxingApiClient {

    private static final String TOKEN_PATH = "/auth/v1.1/token";
    private static final String ROBOT_STATE_PATH = "/robot/v2.0/{robotId}/state";
    private static final String TASK_STATISTICS_PATH = "/statis/v2.0/task";
    private static final String TASK_DETAIL_PATH = "/task/v3/{taskId}";

    private final RestClient restClient;
    private final String appId;
    private final String appSecret;
    private final String appCode;
    private final boolean appCodeScheme;

    public AutoxingApiClient(
            @Value("${app.autoxing.api.base-url:https://apiglobal.autoxing.com}") String baseUrl,
            @Value("${app.autoxing.api.app-id:}") String appId,
            @Value("${app.autoxing.api.app-secret:}") String appSecret,
            @Value("${app.autoxing.api.app-code:}") String appCode,
            // AutoXing's gateway requires the AppCode header as "APPCODE <code>"
            // (Alibaba Cloud API Gateway style) — confirmed against the live global
            // endpoint. Defaults true; set false only for a gateway wanting raw.
            @Value("${app.autoxing.api.appcode-scheme:true}") boolean appCodeScheme) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.appId = appId;
        this.appSecret = appSecret;
        this.appCode = appCode;
        this.appCodeScheme = appCodeScheme;
    }

    /** The value to send in the {@code Authorization} header for gateway auth. */
    private String authorizationHeader() {
        return appCodeScheme ? "APPCODE " + appCode : appCode;
    }

    /** Whether the credentials needed to call the AutoXing API are configured. */
    public boolean isConfigured() {
        return !appId.isBlank() && !appSecret.isBlank() && !appCode.isBlank();
    }

    /**
     * Requests a fresh access token. Signs with {@code MD5(appId + timestamp + appSecret)}
     * and authenticates the gateway with the AppCode in the {@code Authorization} header.
     */
    public AutoxingToken fetchToken() {
        long timestamp = System.currentTimeMillis();
        String sign = md5Hex(appId + timestamp + appSecret);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appId", appId);
        body.put("timestamp", timestamp);
        body.put("sign", sign);

        JsonNode data;
        try {
            JsonNode response = restClient.post()
                    .uri(TOKEN_PATH)
                    .header("Authorization", authorizationHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            data = extractData(response, "fetch token");
        } catch (RestClientResponseException e) {
            // 400 here is the documented "Authentication failed" — flag it so a retry re-signs.
            boolean auth = e.getStatusCode().value() == 400;
            throw new AutoxingApiException(
                    "Failed to fetch AutoXing token: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), auth);
        } catch (AutoxingApiException e) {
            throw e;
        } catch (Exception e) {
            throw new AutoxingApiException("Failed to fetch AutoXing token: " + e.getMessage(), e);
        }

        String token = data.path("token").asText(null);
        if (token == null || token.isBlank()) {
            throw new AutoxingApiException("AutoXing token response contained no token", true);
        }
        long expire = data.path("expireTime").asLong(600);
        return new AutoxingToken(token, expire);
    }

    /** Current live status of a robot (battery, position, errors, charging/manual/e-stop flags). */
    public JsonNode getRobotState(String robotId, String token) {
        try {
            JsonNode response = restClient.get()
                    .uri(ROBOT_STATE_PATH, robotId)
                    .header("X-Token", token)
                    .retrieve()
                    .body(JsonNode.class);
            return extractData(response, "get robot state for " + robotId);
        } catch (RestClientResponseException e) {
            throw new AutoxingApiException(
                    "Failed to get AutoXing robot state for " + robotId + ": "
                            + e.getStatusCode() + " " + e.getResponseBodyAsString(),
                    e.getStatusCode().value() == 400);
        } catch (AutoxingApiException e) {
            throw e;
        } catch (Exception e) {
            throw new AutoxingApiException("Failed to get AutoXing robot state for " + robotId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Daily task statistics for the given devices over {@code [startMs, endMs]} (epoch millis).
     * The window may not exceed 30 days. Returns the {@code data} node with per-category
     * arrays (call/delivery/other/charging/chassis/disinfect/allStatis). Results are cached
     * for 5 minutes on AutoXing's side for identical parameters.
     */
    public JsonNode getTaskStatistics(List<String> deviceIds, long startMs, long endMs, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startTime", startMs);
        body.put("endTime", endMs);
        body.put("deviceIds", deviceIds);
        body.put("isCapacity", true);
        try {
            JsonNode response = restClient.post()
                    .uri(TASK_STATISTICS_PATH)
                    .header("X-Token", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return extractData(response, "get task statistics");
        } catch (RestClientResponseException e) {
            throw new AutoxingApiException(
                    "Failed to get AutoXing task statistics: " + e.getStatusCode() + " " + e.getResponseBodyAsString(),
                    e.getStatusCode().value() == 400);
        } catch (AutoxingApiException e) {
            throw e;
        } catch (Exception e) {
            throw new AutoxingApiException("Failed to get AutoXing task statistics: " + e.getMessage(), e);
        }
    }

    /** Per-task detail/status. {@code needDetail=true} returns task points and actions. */
    public JsonNode getTaskDetail(String taskId, boolean needDetail, String token) {
        try {
            JsonNode response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(TASK_DETAIL_PATH)
                            .queryParam("needDetail", needDetail)
                            .build(taskId))
                    .header("X-Token", token)
                    .retrieve()
                    .body(JsonNode.class);
            return extractData(response, "get task detail for " + taskId);
        } catch (RestClientResponseException e) {
            throw new AutoxingApiException(
                    "Failed to get AutoXing task detail for " + taskId + ": "
                            + e.getStatusCode() + " " + e.getResponseBodyAsString(),
                    e.getStatusCode().value() == 400);
        } catch (AutoxingApiException e) {
            throw e;
        } catch (Exception e) {
            throw new AutoxingApiException("Failed to get AutoXing task detail for " + taskId + ": " + e.getMessage(), e);
        }
    }

    /* ─── Helpers ────────────────────────────────────────────────────────────── */

    /**
     * Unwraps the {@code {status, message, data}} envelope, throwing on a non-200
     * {@code status}. A 400 status is the documented authentication failure.
     */
    private JsonNode extractData(JsonNode response, String action) {
        if (response == null) {
            throw new AutoxingApiException("Empty response from AutoXing while trying to " + action);
        }
        int status = response.path("status").asInt(-1);
        if (status != 200) {
            String message = response.path("message").asText("");
            throw new AutoxingApiException(
                    "AutoXing " + action + " failed: status " + status + " " + message, status == 400);
        }
        return response.path("data");
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AutoxingApiException("MD5 algorithm unavailable for AutoXing signature", e);
        }
    }
}
