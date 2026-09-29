package com.raaspal.robotrecommendation.casereport.adapters.googlesheet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * A Google service-account key, and the signed assertion it trades for an access token.
 *
 * <p>Hand-rolled on jjwt, which the backend already carries for its own logins, rather
 * than pulling in {@code google-auth-library}: the whole flow is one RS256-signed JWT and
 * one form POST (Google's "JWT bearer" grant), and the library would bring Guava, the
 * Google HTTP client and gson along for it.
 *
 * <p>⚠️ Nothing here may put the key in a message. Every error names the problem and the
 * field, never the value.
 */
public record GoogleServiceAccount(String clientEmail,
                                   String privateKeyId,
                                   PrivateKey privateKey,
                                   String tokenUri) {

    static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

    /** Google refuses assertions that live longer than an hour. */
    static final Duration ASSERTION_LIFETIME = Duration.ofHours(1);

    /**
     * Reads a key configured in any of the three shapes it tends to arrive in: the key
     * file's JSON pasted as-is, that JSON base64-encoded (the safe way to put it in an
     * environment variable), or a path to the file (the easy way on a laptop).
     */
    public static GoogleServiceAccount parse(String configured, ObjectMapper mapper) {
        if (configured == null || configured.isBlank()) {
            throw new GoogleSheetException(
                    "No Google service-account key configured (app.googlesheet.credentials)");
        }

        JsonNode node;
        try {
            node = mapper.readTree(resolveJson(configured.trim()));
        } catch (GoogleSheetException e) {
            throw e;
        } catch (Exception e) {
            throw new GoogleSheetException(
                    "The Google service-account key is not valid JSON (app.googlesheet.credentials)");
        }

        String type = node.path("type").asText("");
        if (!"service_account".equals(type)) {
            throw new GoogleSheetException("The configured Google key is not a service-account key "
                    + "(its \"type\" is \"" + type + "\"). Create a key under IAM → Service Accounts.");
        }

        String privateKeyId = node.path("private_key_id").asText("");
        return new GoogleServiceAccount(
                required(node, "client_email"),
                privateKeyId.isBlank() ? null : privateKeyId,
                readPrivateKey(required(node, "private_key")),
                node.path("token_uri").asText(DEFAULT_TOKEN_URI));
    }

    /** The JWT Google exchanges for an access token carrying {@code scope}. */
    public String signedAssertion(String scope, Instant now) {
        JwtBuilder.BuilderHeader header = Jwts.builder().header().type("JWT");
        if (privateKeyId != null) {
            header.keyId(privateKeyId);
        }
        return header.and()
                .issuer(clientEmail)
                // A single string, not jjwt's default one-element array: the audience is
                // the token endpoint, and Google compares it as a string.
                .audience().single(tokenUri)
                .claim("scope", scope)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ASSERTION_LIFETIME)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    static String resolveJson(String configured) {
        if (configured.startsWith("{")) {
            return configured;
        }

        if (configured.startsWith("/") || configured.startsWith("~/") || configured.endsWith(".json")) {
            Path path = Path.of(configured.startsWith("~/")
                    ? System.getProperty("user.home") + configured.substring(1)
                    : configured);
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new GoogleSheetException(
                        "Cannot read the Google service-account key file at " + path);
            }
        }

        try {
            String decoded = new String(Base64.getMimeDecoder().decode(configured), StandardCharsets.UTF_8);
            if (decoded.trim().startsWith("{")) {
                return decoded;
            }
        } catch (IllegalArgumentException e) {
            // Not base64 either; reported below.
        }
        throw new GoogleSheetException("app.googlesheet.credentials is neither the key's JSON, "
                + "base64 of it, nor a path to the .json file");
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new GoogleSheetException("The Google service-account key has no \"" + field + "\"");
        }
        return value;
    }

    private static PrivateKey readPrivateKey(String pem) {
        try {
            // An env var set from pasted JSON often carries the newlines as a literal
            // backslash-n; Jackson has already decoded the real ones.
            String body = pem.replace("\\n", "\n")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new GoogleSheetException(
                    "The Google service-account key's \"private_key\" is not a readable RSA key");
        }
    }
}
