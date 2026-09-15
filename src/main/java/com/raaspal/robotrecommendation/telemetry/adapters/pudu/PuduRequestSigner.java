package com.raaspal.robotrecommendation.telemetry.adapters.pudu;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Signs one request for the PUDU Open Platform gateway.
 *
 * <p>PUDU's cloud API has no token endpoint. Every request carries an HMAC-SHA1 over a
 * canonical string, and the gateway recomputes it from the received headers and path.
 * This is a port of the reference implementation in PUDU's "Authentication Access" doc,
 * cross-checked against a Python port whose output the live gateway accepted (it got as
 * far as key lookup — {@code Invalid API key} for a dummy key, not a signature error).
 *
 * <p>Two traps, both silent 401s, both pinned by tests:
 * <ul>
 *   <li><strong>The signed path keeps {@code /pudu-entry}.</strong> Only the environment
 *       prefixes {@code /release}, {@code /test}, {@code /prepub} are stripped.</li>
 *   <li><strong>Query values are signed decoded.</strong> {@code %23%23%23} signs as
 *       {@code ###}. Keys are sorted; repeated keys join with a comma; a key with only
 *       empty values is written bare.</li>
 * </ul>
 *
 * <p>The date header is formatted by hand rather than with
 * {@link DateTimeFormatter#RFC_1123_DATE_TIME}, which does not zero-pad the day. The
 * reference implementations do ({@code 02 Jan}), and the header must match the signing
 * string byte for byte.
 */
public final class PuduRequestSigner {

    /** Headers to send. {@code xDate} must go out exactly as signed. */
    public record Signature(String xDate, String authorization) {
    }

    static final String ACCEPT = "application/json";
    static final String CONTENT_TYPE = "application/json";

    private static final DateTimeFormatter GMT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private static final String[] ENV_PREFIXES = { "/release", "/test", "/prepub" };

    private final String appKey;
    private final String appSecret;

    public PuduRequestSigner(String appKey, String appSecret) {
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    /** Signs a request made now. */
    public Signature sign(String method, URI uri, String body) {
        return sign(method, uri, body, Instant.now());
    }

    /** Signs a request at a given instant — the seam the tests use for fixed dates. */
    public Signature sign(String method, URI uri, String body, Instant at) {
        String xDate = GMT.format(at);
        String canonical = canonicalPath(uri.getRawPath(), uri.getRawQuery());
        String contentMd5 = "POST".equals(method) ? contentMd5(body == null ? "" : body) : "";
        String signingString = signingString(xDate, method, canonical, contentMd5);
        String signature = hmacSha1Base64(appSecret, signingString);
        String authorization = "hmac id=\"" + appKey + "\", algorithm=\"hmac-sha1\", "
                + "headers=\"x-date\", signature=\"" + signature + "\"";
        return new Signature(xDate, authorization);
    }

    /** The string that is signed, per the reference: five header lines and the path. */
    static String signingString(String xDate, String method, String canonicalPath, String contentMd5) {
        return "x-date: " + xDate + "\n" + method + "\n" + ACCEPT + "\n" + CONTENT_TYPE + "\n"
                + contentMd5 + "\n" + canonicalPath;
    }

    /**
     * Path plus lexically sorted, decoded query — see the class note. {@code rawPath}
     * and {@code rawQuery} are the undecoded forms from the URI.
     */
    static String canonicalPath(String rawPath, String rawQuery) {
        String path = rawPath == null ? "" : rawPath;
        for (String prefix : ENV_PREFIXES) {
            if (path.startsWith(prefix)) {
                path = path.substring(prefix.length());
                break;
            }
        }
        if (path.isEmpty()) {
            path = "/";
        }
        if (rawQuery == null || rawQuery.isEmpty()) {
            return path;
        }

        // Decoded, grouped by key, keys sorted — what url.ParseQuery + sort.Strings do.
        Map<String, List<String>> args = new TreeMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            args.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }

        StringBuilder out = new StringBuilder(path).append('?');
        boolean first = true;
        for (Map.Entry<String, List<String>> e : args.entrySet()) {
            if (!first) out.append('&');
            first = false;
            List<String> nonEmpty = e.getValue().stream().filter(v -> !v.isEmpty()).toList();
            if (nonEmpty.isEmpty()) {
                out.append(e.getKey());
            } else {
                out.append(e.getKey()).append('=').append(String.join(",", nonEmpty));
            }
        }
        return out.toString();
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /** The reference's odd construction: base64 of the <em>hex</em> MD5, not of the digest. */
    static String contentMd5(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return Base64.getEncoder().encodeToString(hex.toString().getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static String hmacSha1Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }
}
