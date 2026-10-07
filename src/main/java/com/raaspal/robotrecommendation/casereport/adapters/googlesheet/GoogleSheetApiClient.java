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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Read-only client for the Google Sheets API v4: one call returns every value on a tab,
 * another the background colours of one column.
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
        String range = quote(tab);
        JsonNode body = withRetry(() -> get(spreadsheetId, range), spreadsheetId, tab);
        JsonNode values = body == null ? null : body.path("values");
        if (values == null || !values.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(values, new TypeReference<List<List<Object>>>() {
        });
    }

    /**
     * The background colour of every cell in one column, from {@code fromRow} down, by the
     * row number the sheet shows. A cell nobody filled reads as white ({@code #ffffff}).
     *
     * <p>The colour as the sheet displays it ({@code effectiveFormat}), so a colour set by a
     * conditional-formatting rule counts the same as one painted by hand. Only that one
     * field of one column is requested, so the answer stays small however wide the tab is.
     *
     * @param column  the column's letter, {@code N}
     * @param fromRow the first row to read, as the sheet numbers it
     * @throws GoogleSheetException as {@link #readTab} does
     */
    public Map<Integer, String> readColumnColours(String spreadsheetId, String tab, String column, int fromRow) {
        String range = quote(tab) + "!" + column + fromRow + ":" + column;
        JsonNode body = withRetry(() -> getColours(spreadsheetId, range), spreadsheetId, tab);
        return coloursByRow(body, fromRow);
    }

    /**
     * The colours out of a {@code spreadsheets.get} answer, by row number. Google leaves
     * out a trailing run of rows with nothing in them, and gives a row with no format as
     * an empty object.
     */
    static Map<Integer, String> coloursByRow(JsonNode body, int fromRow) {
        Map<Integer, String> colours = new LinkedHashMap<>();
        if (body == null) return colours;
        JsonNode data = body.path("sheets").path(0).path("data").path(0);
        // startRow is 0-based and omitted when 0.
        int firstRow = data.has("startRow") ? data.path("startRow").asInt() + 1 : fromRow;
        JsonNode rows = data.path("rowData");
        for (int i = 0; i < rows.size(); i++) {
            JsonNode cell = rows.path(i).path("values").path(0);
            // An empty cell can come without an effective format; its own fill is the
            // next best answer.
            JsonNode format = cell.has("effectiveFormat") ? cell.path("effectiveFormat") : cell.path("userEnteredFormat");
            colours.put(firstRow + i, hex(format));
        }
        return colours;
    }

    /**
     * {@code #rrggbb}, lower case. Google sends each channel as 0-1 and leaves a channel out
     * when it is 0, so pure blue arrives as {@code {"blue": 1}}; no colour at all is white.
     */
    static String hex(JsonNode format) {
        JsonNode colour = format.path("backgroundColorStyle").path("rgbColor");
        if (colour.isMissingNode() || colour.isEmpty()) {
            colour = format.path("backgroundColor");
        }
        if (colour.isMissingNode()) {
            return "#ffffff";
        }
        return String.format("#%02x%02x%02x",
                channel(colour, "red"), channel(colour, "green"), channel(colour, "blue"));
    }

    private static int channel(JsonNode colour, String name) {
        return (int) Math.round(Math.max(0, Math.min(1, colour.path(name).asDouble(0))) * 255);
    }

    private static String quote(String tab) {
        return "'" + tab.replace("'", "''") + "'";
    }

    /**
     * One retry, for timeouts and dropped connections only - an HTTP error status is
     * deterministic and is not repeated.
     */
    private JsonNode withRetry(Supplier<JsonNode> call, String spreadsheetId, String tab) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            throw translate(e, spreadsheetId, tab);
        } catch (GoogleSheetException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Google Sheets read failed ({}), retrying once", e.getMessage());
            try {
                return call.get();
            } catch (RestClientResponseException retryError) {
                throw translate(retryError, spreadsheetId, tab);
            } catch (GoogleSheetException retryError) {
                throw retryError;
            } catch (Exception retryError) {
                throw new GoogleSheetException(
                        "Google Sheets read failed twice: " + retryError.getMessage(), retryError);
            }
        }
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

    private JsonNode getColours(String spreadsheetId, String range) {
        return sheets.get()
                .uri(uri -> uri.path("/spreadsheets/{id}")
                        .queryParam("ranges", "{range}")
                        .queryParam("includeGridData", "true")
                        .queryParam("fields", "{fields}")
                        .build(spreadsheetId, range,
                                "sheets(data(startRow,rowData(values("
                                        + "effectiveFormat(backgroundColor,backgroundColorStyle),"
                                        + "userEnteredFormat(backgroundColor,backgroundColorStyle)))))"))
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
