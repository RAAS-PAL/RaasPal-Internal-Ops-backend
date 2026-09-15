package com.raaspal.robotrecommendation.telemetry.adapters.pudu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin HTTP client for the PUDU Open Platform cloud API.
 *
 * <p>Follows the AutoXing client's conventions — a per-client {@link RestClient},
 * credentials via {@code @Value}, an {@link #isConfigured()} guard, errors wrapped in
 * {@link PuduApiException} — with one structural difference: <strong>there is no token
 * service.</strong> PUDU signs every request with {@link PuduRequestSigner}, so there is
 * nothing to cache, refresh, or retry after; a 401 means the key or secret is wrong.
 *
 * <p>The URI is built first, signed, then sent as the same object, so the path and
 * query the gateway verifies are exactly the ones that were signed.
 *
 * <p>PUDU wraps responses as {@code {message, data, trace_id}}. Read raw and parsed
 * with Jackson, as the AutoXing client does, so an odd {@code Content-Type} cannot
 * make Spring's converters refuse a perfectly good body.
 *
 * <p><strong>Regional host.</strong> RAASPAL's account is on the overseas node
 * (Japan/Korea/Singapore) — the same {@code css} prefix as the {@code css.pudutech.com}
 * portal. The other nodes are China ({@code open-platform}), Germany ({@code csg-}) and
 * the US ({@code csu-}); the signing is identical, only the host differs.
 */
@Slf4j
@Component
public class PuduApiClient {

    static final String HEALTH_PATH = "/data-open-platform-service/v1/api/healthCheck";
    static final String DELIVERY_PAGING_PATH = "/data-board/v1/analysis/task/delivery/paging";

    /** PUDU caps {@code limit} at 20 on the data-board paging endpoints. */
    static final int PAGE_SIZE = 20;

    /**
     * A month of a large shop at 20 rows a page is a few dozen pages; this is a guard
     * against an endpoint that never reports a total, not a working limit.
     */
    static final int MAX_PAGES = 500;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String appKey;
    private final String appSecret;
    private final PuduRequestSigner signer;

    public PuduApiClient(
            @Value("${app.pudu.api.base-url:https://css-open-platform.pudutech.com/pudu-entry}") String baseUrl,
            @Value("${app.pudu.api.app-key:}") String appKey,
            @Value("${app.pudu.api.app-secret:}") String appSecret,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.signer = new PuduRequestSigner(appKey, appSecret);
    }

    /** Whether the credentials needed to call the PUDU API are configured. */
    public boolean isConfigured() {
        return !appKey.isBlank() && !appSecret.isBlank();
    }

    /** The credentials-only health check. Succeeds iff the key, secret and node are right. */
    public JsonNode healthCheck() {
        return get(UriComponentsBuilder.fromUriString(baseUrl + HEALTH_PATH).build(true).toUri(),
                "health check");
    }

    /**
     * Every row of the delivery statistics list for {@code [startSec, endSec]}, one row
     * per {@code groupBy} unit per {@code timeUnit} bucket, all pages concatenated.
     *
     * <p>With {@code groupBy=robot} and {@code timeUnit=day} each row is one robot's day:
     * {@code sn}, {@code robot_name}, {@code product_name}, {@code shop_id},
     * {@code shop_name}, {@code task_time} (Y-m-d), {@code mileage} (km), {@code duration}
     * (h), {@code task_count}, {@code table_count}, {@code tray_count}, {@code speed} (m/s).
     *
     * @param startSec       epoch seconds, inclusive
     * @param endSec         epoch seconds, inclusive
     * @param timezoneOffset hours from UTC the buckets are cut in, -12..14
     * @param shopId         narrows to one store, or null for the whole account
     */
    public List<JsonNode> deliveryRows(long startSec, long endSec, int timezoneOffset,
                                       Long shopId, String groupBy, String timeUnit) {
        List<JsonNode> rows = new ArrayList<>();
        int offset = 0;
        int page = 0;
        long total;

        do {
            UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl + DELIVERY_PAGING_PATH)
                    .queryParam("start_time", startSec)
                    .queryParam("end_time", endSec)
                    .queryParam("timezone_offset", timezoneOffset)
                    .queryParam("group_by", groupBy)
                    .queryParam("time_unit", timeUnit)
                    .queryParam("offset", offset)
                    .queryParam("limit", PAGE_SIZE);
            if (shopId != null) {
                b.queryParam("shop_id", shopId);
            }

            JsonNode data = get(b.build(true).toUri(), "read delivery statistics page " + page);
            total = data.path("total").asLong(0);
            JsonNode list = data.path("list");
            if (list.isArray()) {
                list.forEach(rows::add);
                offset += list.size();
                if (list.isEmpty()) {
                    break;  // a page with nothing on it means we are past the end, whatever total says
                }
            } else {
                break;
            }
            page++;
        } while (offset < total && page < MAX_PAGES);

        if (offset < total) {
            log.warn("Stopped reading PUDU delivery statistics after {} pages with {} of {} rows; "
                    + "results are incomplete", page, offset, total);
        }
        return rows;
    }

    private JsonNode get(URI uri, String action) {
        PuduRequestSigner.Signature sig = signer.sign("GET", uri, null);
        return restClient.get()
                .uri(uri)
                .header("Accept", PuduRequestSigner.ACCEPT)
                .header("Content-Type", PuduRequestSigner.CONTENT_TYPE)
                .header("x-date", sig.xDate())
                .header("Authorization", sig.authorization())
                .header("Language", "en")
                .exchange((request, response) -> {
                    try {
                        int status = response.getStatusCode().value();
                        byte[] bytes = StreamUtils.copyToByteArray(response.getBody());
                        String text = new String(bytes, StandardCharsets.UTF_8);
                        if (status >= 400) {
                            throw new PuduApiException(
                                    "Failed to " + action + ": HTTP " + status + " " + text,
                                    status == 401 || status == 403);
                        }
                        if (bytes.length == 0) {
                            throw new PuduApiException("Empty response from PUDU while trying to " + action);
                        }
                        return extractData(objectMapper.readTree(bytes), action);
                    } catch (IOException e) {
                        throw new PuduApiException("Failed to " + action + ": " + e.getMessage(), e);
                    }
                });
    }

    /**
     * Unwraps {@code {message, data, trace_id}}. The docs say a success message is
     * {@code SUCCESS}; the examples say {@code ok}. So success is judged by the presence
     * of {@code data}, and the message is only reported when it is missing.
     */
    private static JsonNode extractData(JsonNode response, String action) {
        JsonNode data = response.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new PuduApiException("PUDU " + action + " returned no data: "
                    + response.path("message").asText("(no message)")
                    + " trace " + response.path("trace_id").asText("-"));
        }
        return data;
    }
}
