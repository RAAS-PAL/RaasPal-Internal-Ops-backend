package com.raaspal.robotrecommendation.telemetry.adapters.autoxing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
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
 *
 * <p>Responses are read raw via {@code exchange()} and parsed with Jackson directly,
 * because AutoXing sends a malformed {@code Content-Type: json;charset=UTF-8} header
 * (no {@code /}) that Spring's message converters reject.
 */
@Slf4j
@Component
public class AutoxingApiClient {

    private static final String TOKEN_PATH = "/auth/v1.1/token";
    private static final String ROBOT_STATE_PATH = "/robot/v2.0/{robotId}/state";
    private static final String TASK_STATISTICS_PATH = "/statis/v2.0/task";
    private static final String TASK_DETAIL_PATH = "/task/v3/{taskId}";
    private static final String ROBOT_LIST_PATH = "/robot/v1.1/list";
    private static final String BUSINESS_LIST_PATH = "/business/v1.1/list";
    private static final String BUILDING_LIST_PATH = "/building/v1.1/list";
    private static final String AREA_LIST_PATH = "/map/v1.1/area/list";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
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
            @Value("${app.autoxing.api.appcode-scheme:true}") boolean appCodeScheme,
            ObjectMapper objectMapper) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
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

        JsonNode data = exchangeForData(
                restClient.post()
                        .uri(TOKEN_PATH)
                        .header("Authorization", authorizationHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                "fetch token");

        String token = data.path("token").asText(null);
        if (token == null || token.isBlank()) {
            throw new AutoxingApiException("AutoXing token response contained no token", true);
        }
        long expire = data.path("expireTime").asLong(600);
        return new AutoxingToken(token, expire);
    }

    /** Current live status of a robot (battery, position, errors, charging/manual/e-stop flags). */
    public JsonNode getRobotState(String robotId, String token) {
        return exchangeForData(
                restClient.get()
                        .uri(ROBOT_STATE_PATH, robotId)
                        .header("X-Token", token),
                "get robot state for " + robotId);
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
        return exchangeForData(
                restClient.post()
                        .uri(TASK_STATISTICS_PATH)
                        .header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                "get task statistics");
    }

    /** Per-task detail/status. {@code needDetail=true} returns task points and actions. */
    public JsonNode getTaskDetail(String taskId, boolean needDetail, String token) {
        return exchangeForData(
                restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path(TASK_DETAIL_PATH)
                                .queryParam("needDetail", needDetail)
                                .build(taskId))
                        .header("X-Token", token),
                "get task detail for " + taskId);
    }

    /**
     * Robot summary for one robot id — carries {@code model} (an AutoXing category,
     * e.g. "餐厅"), {@code name} (often blank) and {@code businessId}. Returns the
     * first matching entry, or a missing node when the robot is not found.
     */
    public JsonNode getRobotSummary(String robotId, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyWord", robotId);
        body.put("pageSize", 10);
        body.put("pageNum", 1);
        JsonNode data = exchangeForData(
                restClient.post()
                        .uri(ROBOT_LIST_PATH)
                        .header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                "get robot list");
        for (JsonNode entry : entries(data)) {
            if (robotId.equalsIgnoreCase(entry.path("robotId").asText())) {
                return entry;
            }
        }
        return entries(data).isEmpty() ? MissingNode.getInstance() : entries(data).get(0);
    }

    /** All businesses (tenants) visible to the account — used to resolve a customer name. */
    public JsonNode getBusinessList(String token) {
        return exchangeForData(
                restClient.post()
                        .uri(BUSINESS_LIST_PATH)
                        .header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of()),
                "get business list");
    }

    /** All buildings (sites) visible to the account — used to resolve a site name. */
    public JsonNode getBuildingList(String token) {
        return exchangeForData(
                restClient.post()
                        .uri(BUILDING_LIST_PATH)
                        .header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of()),
                "get building list");
    }

    /** Areas (zones/floors) a robot operates in — carries area name, floor and buildingId. */
    public JsonNode getAreaList(String robotId, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("robotId", robotId);
        body.put("pageSize", 0);
        body.put("pageNum", 1);
        return exchangeForData(
                restClient.post()
                        .uri(AREA_LIST_PATH)
                        .header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                "get area list");
    }

    /**
     * The array of rows in a list response. AutoXing is inconsistent: business and
     * building lists use {@code lists}, area/robot lists use {@code list}.
     */
    public static JsonNode entries(JsonNode data) {
        if (data == null) {
            return MissingNode.getInstance();
        }
        JsonNode plural = data.path("lists");
        if (plural.isArray()) {
            return plural;
        }
        JsonNode singular = data.path("list");
        return singular.isArray() ? singular : MissingNode.getInstance();
    }

    /* ─── Helpers ────────────────────────────────────────────────────────────── */

    /**
     * Sends the request and reads the response body directly (bypassing Spring's
     * Content-Type-based conversion, which AutoXing's malformed header breaks),
     * then unwraps the {@code {status, message, data}} envelope. HTTP 4xx and a
     * non-200 envelope status both raise an {@link AutoxingApiException}, flagging
     * the auth-failure cases so the caller can re-authenticate and retry once.
     */
    private JsonNode exchangeForData(RestClient.RequestHeadersSpec<?> spec, String action) {
        return spec.exchange((request, response) -> {
            try {
                int httpStatus = response.getStatusCode().value();
                byte[] bytes = StreamUtils.copyToByteArray(response.getBody());
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (httpStatus >= 400) {
                    throw new AutoxingApiException(
                            "Failed to " + action + ": HTTP " + httpStatus + " " + text,
                            httpStatus == 400 || httpStatus == 401);
                }
                if (bytes.length == 0) {
                    throw new AutoxingApiException("Empty response from AutoXing while trying to " + action);
                }
                return extractData(objectMapper.readTree(bytes), action);
            } catch (IOException e) {
                throw new AutoxingApiException("Failed to " + action + ": " + e.getMessage(), e);
            }
        });
    }

    /**
     * Unwraps the {@code {status, message, data}} envelope, throwing on a non-200
     * {@code status}. A 400 status is the documented authentication failure.
     */
    private JsonNode extractData(JsonNode response, String action) {
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
