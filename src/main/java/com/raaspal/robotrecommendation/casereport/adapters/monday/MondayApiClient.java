package com.raaspal.robotrecommendation.casereport.adapters.monday;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin GraphQL client for the monday.com API v2. Stateless - it executes a
 * query and returns the {@code data} node; shaping the response into domain
 * objects belongs to the caller.
 *
 * <p>monday answers with HTTP 200 even when a query fails, putting the failure
 * in an {@code errors} array in the body, so success cannot be inferred from
 * the status code alone - see {@link #unwrap}.
 */
@Slf4j
@Component
public class MondayApiClient {

    private final RestClient restClient;
    private final String token;
    private final String apiVersion;

    public MondayApiClient(
            @Value("${app.monday.api.base-url:https://api.monday.com/v2}") String baseUrl,
            @Value("${app.monday.api.token:}") String token,
            @Value("${app.monday.api.version:2026-07}") String apiVersion) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.token = token;
        this.apiVersion = apiVersion;
    }

    /** Whether a monday API token is configured. */
    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }

    /**
     * Executes a GraphQL query and returns its {@code data} node.
     *
     * @param query     the GraphQL document
     * @param variables values for the query's declared variables; may be null
     * @throws MondayApiException on transport failure or any GraphQL error
     */
    public JsonNode execute(String query, Map<String, Object> variables) {
        if (!isConfigured()) {
            throw new MondayApiException("monday API token is not configured (app.monday.api.token)");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("variables", variables == null ? Map.of() : variables);

        JsonNode response;
        try {
            response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", token)
                    .header("API-Version", apiVersion)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MondayApiException(
                    "monday API call failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new MondayApiException("monday API call failed: " + e.getMessage(), e);
        }

        return unwrap(response);
    }

    /** Returns the {@code data} node, failing loudly on GraphQL errors. */
    private JsonNode unwrap(JsonNode response) {
        if (response == null) {
            throw new MondayApiException("monday API returned an empty body");
        }

        String requestId = response.path("extensions").path("request_id").asText("unknown");

        JsonNode errors = response.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            List<String> messages = new ArrayList<>();
            errors.forEach(error -> messages.add(error.path("message").asText(error.toString())));
            throw new MondayApiException(
                    "monday API returned errors (request " + requestId + "): " + String.join(" | ", messages));
        }

        // Auth failures arrive in this shape instead of the errors array.
        JsonNode errorMessage = response.path("error_message");
        if (!errorMessage.isMissingNode() && !errorMessage.asText().isBlank()) {
            throw new MondayApiException(
                    "monday API returned an error (request " + requestId + "): " + errorMessage.asText());
        }

        JsonNode data = response.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new MondayApiException("monday API response contained no data node: " + response);
        }

        log.debug("monday API request {} succeeded", requestId);
        return data;
    }
}
