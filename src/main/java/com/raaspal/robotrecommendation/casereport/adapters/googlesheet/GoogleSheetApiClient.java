package com.raaspal.robotrecommendation.casereport.adapters.googlesheet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Read-only client for the Google Sheets API v4: one call returns every value on a tab.
 *
 * <p>Authenticates as a service account, so the sheet must be shared with that account's
 * email as a Viewer - exactly like sharing it with a person. A sheet that is not shared
 * answers 403, which is translated into a message naming the address to share with.
 *
 * <p>Values are requested <em>unformatted</em>: a number arrives as a number, and a date
 * as its serial day count rather than whatever the cell's display format prints
 * ({@code 26-Jul-23}). A display string would have to be parsed back, and its format
 * belongs to whoever last formatted the column. See {@link SheetCells#date}.
 */
@Slf4j
@Component
public class GoogleSheetApiClient {

    /** Read-only. The sync never writes to the sheet, so the token cannot either. */
    static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly";

    /** Refresh this long before the token expires, so a read never starts on a dying one. */
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(1);

    private final RestClient sheets;
    private final RestClient oauth;
    private final String credentials;
    private final ObjectMapper objectMapper;

    // Parsed on first use rather than at startup: a malformed key must fail the sheet
    // read that needs it, not stop the whole backend from booting.
    private GoogleServiceAccount account;
    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public GoogleSheetApiClient(
            @Value("${app.googlesheet.api.base-url:https://sheets.googleapis.com/v4}") String baseUrl,
            @Value("${app.googlesheet.credentials:}") String credentials,
            @Value("${app.googlesheet.api.connect-timeout-seconds:15}") int connectTimeoutSeconds,
            @Value("${app.googlesheet.api.read-timeout-seconds:60}") int readTimeoutSeconds,
            ObjectMapper objectMapper) {

        // Finite timeouts for the reason MondayApiClient spells out: without one, an
        // unanswered request parks the sync thread for good.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        this.sheets = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.oauth = RestClient.builder().requestFactory(requestFactory).build();
        this.credentials = credentials;
        this.objectMapper = objectMapper;
    }

    /** Whether a service-account key is configured at all. */
    public boolean isConfigured() {
        return credentials != null && !credentials.isBlank();
    }

    /**
     * The address a sheet must be shared with, or null when no readable key is configured.
     * For the preview, so whoever owns the sheet can be told exactly what to type.
     */
    public String serviceAccountEmail() {
        try {
            return account().clientEmail();
        } catch (GoogleSheetException e) {
            return null;
        }
    }

    /**
     * Every value on one tab, as rows of cells. Google leaves trailing empty cells off
     * each row and trailing empty rows off the end, so rows vary in length.
     *
     * @throws GoogleSheetException on a missing key, a sheet not shared with the service
     *                              account, an unknown tab, or a transport failure
     */
    public List<List<Object>> readTab(String spreadsheetId, String tab) {
        String range = "'" + tab.replace("'", "''") + "'";

        JsonNode body;
        try {
            body = get(spreadsheetId, range);
        } catch (RestClientResponseException e) {
            throw translate(e, spreadsheetId, tab);
        } catch (GoogleSheetException e) {
            throw e;
        } catch (Exception e) {
            // One retry, for timeouts and dropped connections only - an HTTP error status
            // is deterministic and is not repeated.
            log.warn("Google Sheets read failed ({}), retrying once", e.getMessage());
            try {
                body = get(spreadsheetId, range);
            } catch (RestClientResponseException retryError) {
                throw translate(retryError, spreadsheetId, tab);
            } catch (GoogleSheetException retryError) {
                throw retryError;
            } catch (Exception retryError) {
                throw new GoogleSheetException(
                        "Google Sheets read failed twice: " + retryError.getMessage(), retryError);
            }
        }

        JsonNode values = body == null ? null : body.path("values");
        if (values == null || !values.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(values, new TypeReference<List<List<Object>>>() {
        });
    }

    private JsonNode get(String spreadsheetId, String range) {
        return sheets.get()
                .uri(uri -> uri.path("/spreadsheets/{id}/values/{range}")
                        .queryParam("valueRenderOption", "UNFORMATTED_VALUE")
                        .queryParam("dateTimeRenderOption", "SERIAL_NUMBER")
                        .queryParam("majorDimension", "ROWS")
                        .build(spreadsheetId, range))
                .header("Authorization", "Bearer " + accessToken())
                .retrieve()
                .body(JsonNode.class);
    }

    private GoogleSheetException translate(RestClientResponseException e, String spreadsheetId, String tab) {
        int status = e.getStatusCode().value();
        String body = e.getResponseBodyAsString();

        if (status == 403) {
            String email = serviceAccountEmail();
            return new GoogleSheetException("The service account cannot open spreadsheet "
                    + spreadsheetId + ". Share the sheet with "
                    + (email == null ? "the service account's email" : email)
                    + " as a Viewer.", e);
        }
        if (status == 404) {
            return new GoogleSheetException("Spreadsheet " + spreadsheetId
                    + " was not found. Check app.googlesheet.aot.spreadsheet-id: it is the long id "
                    + "between /d/ and /edit in the sheet's URL.", e);
        }
        if (status == 400 && body.contains("Unable to parse range")) {
            return new GoogleSheetException("Spreadsheet " + spreadsheetId + " has no tab named '"
                    + tab + "'. Tab names are case-sensitive.", e);
        }
        return new GoogleSheetException("Google Sheets read failed: " + status + " " + body, e);
    }

    /** A cached access token, fetched again shortly before it expires. */
    private synchronized String accessToken() {
        Instant now = Instant.now();
        if (accessToken != null && now.isBefore(accessTokenExpiresAt.minus(TOKEN_MARGIN))) {
            return accessToken;
        }

        GoogleServiceAccount serviceAccount = account();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", serviceAccount.signedAssertion(SCOPE, now));

        JsonNode response;
        try {
            response = oauth.post()
                    .uri(serviceAccount.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // Google's error body is {"error": ..., "error_description": ...}; it never
            // echoes the assertion, so it is safe to pass on.
            throw new GoogleSheetException("Google refused the service-account key: "
                    + e.getStatusCode().value() + " " + e.getResponseBodyAsString()
                    + " (a revoked key, or a server clock more than a few minutes off)", e);
        } catch (Exception e) {
            throw new GoogleSheetException("Could not reach Google's token endpoint: " + e.getMessage(), e);
        }

        String token = response == null ? "" : response.path("access_token").asText("");
        if (token.isBlank()) {
            throw new GoogleSheetException("Google's token endpoint returned no access_token");
        }

        accessToken = token;
        accessTokenExpiresAt = now.plusSeconds(response.path("expires_in").asLong(3600));
        return accessToken;
    }

    private synchronized GoogleServiceAccount account() {
        if (account == null) {
            account = GoogleServiceAccount.parse(credentials, objectMapper);
        }
        return account;
    }
}
