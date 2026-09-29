package com.raaspal.robotrecommendation.casereport.adapters.googlesheet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a service-account key in each shape it is configured in, and the assertion it
 * signs. The key is generated here; no real key is involved.
 */
class GoogleServiceAccountTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static KeyPair keys;
    private static String keyJson;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();

        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        keyJson = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("type", "service_account")
                .put("project_id", "test-project")
                .put("private_key_id", "kid-123")
                .put("private_key", pem)
                .put("client_email", "reader@test-project.iam.gserviceaccount.com")
                .put("token_uri", "https://oauth2.googleapis.com/token"));
    }

    @Test
    void readsTheJsonAsIs() {
        GoogleServiceAccount account = GoogleServiceAccount.parse(keyJson, MAPPER);

        assertThat(account.clientEmail()).isEqualTo("reader@test-project.iam.gserviceaccount.com");
        assertThat(account.privateKeyId()).isEqualTo("kid-123");
        assertThat(account.tokenUri()).isEqualTo("https://oauth2.googleapis.com/token");
    }

    @Test
    void readsBase64OfTheJson() {
        String encoded = Base64.getEncoder().encodeToString(keyJson.getBytes(StandardCharsets.UTF_8));

        assertThat(GoogleServiceAccount.parse(encoded, MAPPER).clientEmail())
                .isEqualTo("reader@test-project.iam.gserviceaccount.com");
    }

    @Test
    void readsAPathToTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("key.json");
        Files.writeString(file, keyJson);

        assertThat(GoogleServiceAccount.parse(file.toString(), MAPPER).clientEmail())
                .isEqualTo("reader@test-project.iam.gserviceaccount.com");
    }

    /** JSON pasted into an env var often arrives with its newlines as a literal \n. */
    @Test
    void toleratesLiteralBackslashNInTheKey() throws Exception {
        JsonNode node = MAPPER.readTree(keyJson);
        String flattened = node.path("private_key").asText().replace("\n", "\\n");
        String json = MAPPER.writeValueAsString(((com.fasterxml.jackson.databind.node.ObjectNode) node)
                .put("private_key", flattened));

        assertThat(GoogleServiceAccount.parse(json, MAPPER).privateKey()).isNotNull();
    }

    @Test
    void refusesAnythingThatIsNotAServiceAccountKey() {
        assertThatThrownBy(() -> GoogleServiceAccount.parse("", MAPPER))
                .isInstanceOf(GoogleSheetException.class);
        assertThatThrownBy(() -> GoogleServiceAccount.parse("{\"type\":\"authorized_user\"}", MAPPER))
                .isInstanceOf(GoogleSheetException.class)
                .hasMessageContaining("not a service-account key");
        assertThatThrownBy(() -> GoogleServiceAccount.parse("not-a-key", MAPPER))
                .isInstanceOf(GoogleSheetException.class);
    }

    @Test
    void aBrokenKeyIsReportedWithoutPrintingIt() {
        String broken = keyJson.replace("\"private_key\":\"-----BEGIN", "\"private_key\":\"garbage-----BEGIN");

        assertThatThrownBy(() -> GoogleServiceAccount.parse(broken, MAPPER))
                .isInstanceOf(GoogleSheetException.class)
                .hasMessageNotContaining("BEGIN");
    }

    @Test
    void theAssertionCarriesWhatGoogleChecks() throws Exception {
        GoogleServiceAccount account = GoogleServiceAccount.parse(keyJson, MAPPER);
        Instant now = Instant.now();

        String jwt = account.signedAssertion(GoogleSheetApiClient.SCOPE, now);

        Claims claims = Jwts.parser().verifyWith(keys.getPublic()).build()
                .parseSignedClaims(jwt).getPayload();
        assertThat(claims.getIssuer()).isEqualTo(account.clientEmail());
        assertThat(claims.get("scope", String.class))
                .isEqualTo("https://www.googleapis.com/auth/spreadsheets.readonly");
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(3_600_000L);

        // Google compares aud as a string; jjwt's default is a one-element array.
        String[] parts = jwt.split("\\.");
        JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(payload.path("aud").isTextual()).isTrue();
        assertThat(payload.path("aud").asText()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(header.path("alg").asText()).isEqualTo("RS256");
        assertThat(header.path("kid").asText()).isEqualTo("kid-123");
    }
}
