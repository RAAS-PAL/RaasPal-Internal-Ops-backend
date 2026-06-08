package com.raaspal.robotrecommendation.cvte.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signs and sends requests to the Kava Open Gateway API following the
 * authentication rules in the Kava API document (see x-kv-* headers).
 * The app secret is used only to compute digests — it is never logged,
 * stored on a request, or included in any response.
 */
@Component
public class KavaApiClient {

    private static final Logger log = LoggerFactory.getLogger(KavaApiClient.class);

    private static final String DEVICE_PAGE_PATH = "/v1/device/page";
    private static final String DEVICE_DETAIL_PATH = "/v1/device/detail/";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String appId;
    private final String appSecret;
    private final String signType;

    public KavaApiClient(
            @Value("${app.cvte.kava.base-url:}") String baseUrl,
            @Value("${app.cvte.kava.app-id:}") String appId,
            @Value("${app.cvte.kava.app-secret:}") String appSecret,
            @Value("${app.cvte.kava.sign-type:hmac}") String signType,
            ObjectMapper objectMapper) {
        this.restClient = RestClient.builder().baseUrl(hasText(baseUrl) ? baseUrl : "https://localhost").build();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.appId = appId;
        this.appSecret = appSecret;
        this.signType = signType;
    }

    /** True once the base URL, app ID, and secret have all been provided via environment variables. */
    public boolean isConfigured() {
        return hasText(baseUrl) && hasText(appId) && hasText(appSecret);
    }

    /** POST /v1/device/page — search devices by factory SN, device name, and/or org code. */
    public KavaApiResult<List<KavaDeviceStatus>> searchDevices(String factorySn, String deviceName, String orgCode, int pageSize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("current", 1);
        body.put("pageSize", pageSize);
        if (hasText(factorySn)) {
            body.put("factorySn", factorySn.trim());
        }
        if (hasText(deviceName)) {
            body.put("deviceName", deviceName.trim());
        }
        if (hasText(orgCode)) {
            body.put("orgCode", orgCode.trim());
        }

        ParsedResponse response = post(DEVICE_PAGE_PATH, body);
        List<KavaDeviceStatus> devices = new ArrayList<>();
        JsonNode rows = response.data().path("data");
        if (rows.isArray()) {
            for (JsonNode row : rows) {
                devices.add(toDeviceStatus(row));
            }
        }
        return new KavaApiResult<>(devices, response.code(), response.message());
    }

    /** GET /v1/device/detail/{deviceId} — current status snapshot for one device. */
    public KavaApiResult<KavaDeviceStatus> getDeviceDetail(long deviceId) {
        ParsedResponse response = get(DEVICE_DETAIL_PATH + deviceId);
        JsonNode data = response.data();
        KavaDeviceStatus status = (data == null || data.isMissingNode() || data.isNull()) ? null : toDeviceStatus(data);
        return new KavaApiResult<>(status, response.code(), response.message());
    }

    // ─── HTTP + signing ───────────────────────────────────────────────────────

    private ParsedResponse post(String path, Map<String, Object> body) {
        byte[] bodyBytes;
        try {
            bodyBytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new KavaApiException("Failed to serialise Kava request body for " + path, e);
        }

        HttpHeaders headers = signedHeaders(path, bodyBytes);
        try {
            String response = restClient.post()
                    .uri(path)
                    .headers(h -> h.addAll(headers))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bodyBytes)
                    .retrieve()
                    .body(String.class);
            return parse(response, path);
        } catch (RestClientException e) {
            log.warn("Kava API call failed: POST {} — {}", path, e.getMessage());
            throw new KavaApiException("Kava API call failed for " + path, e);
        }
    }

    private ParsedResponse get(String path) {
        HttpHeaders headers = signedHeaders(path, null);
        try {
            String response = restClient.get()
                    .uri(path)
                    .headers(h -> h.addAll(headers))
                    .retrieve()
                    .body(String.class);
            return parse(response, path);
        } catch (RestClientException e) {
            log.warn("Kava API call failed: GET {} — {}", path, e.getMessage());
            throw new KavaApiException("Kava API call failed for " + path, e);
        }
    }

    /** Builds the x-kv-* headers and computes x-kv-sign per the signing rules (Steps 1-3 in the doc). */
    private HttpHeaders signedHeaders(String reqPath, byte[] body) {
        Map<String, String> signingParams = new LinkedHashMap<>();
        signingParams.put("x-kv-app-id", appId);
        signingParams.put("x-kv-timestamp", String.valueOf(System.currentTimeMillis()));
        signingParams.put("x-kv-sign-type", signType);
        signingParams.put("x-kv-req-path", reqPath);
        if (body != null && body.length > 0) {
            signingParams.put("x-kv-content-md5", KavaSignatureUtil.contentMd5(body));
        }

        String sign = KavaSignatureUtil.sign(signingParams, appSecret, signType);

        HttpHeaders headers = new HttpHeaders();
        signingParams.forEach(headers::add);
        headers.add("x-kv-sign", sign);
        return headers;
    }

    private ParsedResponse parse(String response, String path) {
        try {
            JsonNode root = objectMapper.readTree(response);
            String code = textOrNull(root.get("code"));
            String message = textOrNull(root.get("msg"));
            JsonNode data = root.path("data");
            return new ParsedResponse(code, message, data);
        } catch (JsonProcessingException e) {
            throw new KavaApiException("Failed to parse Kava response for " + path, e);
        }
    }

    // ─── Field mapping ────────────────────────────────────────────────────────

    private KavaDeviceStatus toDeviceStatus(JsonNode node) {
        return new KavaDeviceStatus(
                longOrNull(node.get("deviceId")),
                textOrNull(node.get("factorySn")),
                textOrNull(node.get("deviceName")),
                textOrNull(node.get("deviceRunningState")),
                doubleOrNull(node.get("electricity")),
                booleanOrNull(node.get("onlineStatus"))
        );
    }

    private static Long longOrNull(JsonNode node) {
        return (node == null || node.isNull() || node.isMissingNode()) ? null : node.asLong();
    }

    private static Double doubleOrNull(JsonNode node) {
        return (node == null || node.isNull() || node.isMissingNode()) ? null : node.asDouble();
    }

    private static String textOrNull(JsonNode node) {
        return (node == null || node.isNull() || node.isMissingNode()) ? null : node.asText();
    }

    /** The list endpoint returns a boolean; the detail endpoint returns 1/0 — normalise both. */
    private static Boolean booleanOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String text = node.asText();
        if ("1".equals(text) || "true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("0".equals(text) || "false".equalsIgnoreCase(text)) {
            return false;
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ParsedResponse(String code, String message, JsonNode data) {
    }
}
