package com.raaspal.robotrecommendation.telemetry.adapters.pudu;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signer against golden values from an independent port.
 *
 * <p>The expected signatures were produced by a Python implementation of the same
 * reference algorithm — the one the live PUDU gateway accepted structurally (it
 * rejected a dummy key with {@code Invalid API key}, i.e. it got past signature
 * parsing to key lookup). A Java port that agrees with it byte for byte on these five
 * shapes is signing the way the gateway expects; a port that only agreed with itself
 * would prove nothing.
 */
class PuduRequestSignerTest {

    private static final Instant AT = Instant.parse("2026-09-15T03:00:00Z");
    private static final String X_DATE = "Tue, 15 Sep 2026 03:00:00 GMT";
    private static final PuduRequestSigner SIGNER = new PuduRequestSigner("demo-key", "demo-secret");

    @Test
    void healthCheckWithNoQuery() {
        PuduRequestSigner.Signature s = SIGNER.sign("GET", URI.create(
                "https://css-open-platform.pudutech.com/pudu-entry/data-open-platform-service/v1/api/healthCheck"),
                null, AT);

        assertThat(s.xDate()).isEqualTo(X_DATE);
        assertThat(s.authorization()).isEqualTo(
                "hmac id=\"demo-key\", algorithm=\"hmac-sha1\", headers=\"x-date\", "
                        + "signature=\"inSBRSYOS6F/EEFUlhHBRlD5pDE=\"");
    }

    /** Query keys are sorted; the path keeps /pudu-entry. */
    @Test
    void deliveryQueryIsSortedAndKeepsThePuduEntryPrefix() {
        String canonical = PuduRequestSigner.canonicalPath(
                "/pudu-entry/data-board/v1/analysis/task/delivery",
                "timezone_offset=7&start_time=1693497600&end_time=1693670399&shop_id=331300000&time_unit=day");
        assertThat(canonical).isEqualTo(
                "/pudu-entry/data-board/v1/analysis/task/delivery"
                        + "?end_time=1693670399&shop_id=331300000&start_time=1693497600&time_unit=day&timezone_offset=7");

        PuduRequestSigner.Signature s = SIGNER.sign("GET", URI.create(
                "https://css-open-platform.pudutech.com/pudu-entry/data-board/v1/analysis/task/delivery"
                        + "?timezone_offset=7&start_time=1693497600&end_time=1693670399&shop_id=331300000&time_unit=day"),
                null, AT);
        assertThat(s.authorization()).endsWith("signature=\"Sq+m2JBEvE5clyDi2+hnI0fw6yM=\"");
    }

    /** The reference's own special-character case: values are signed decoded. */
    @Test
    void queryValuesAreSignedDecoded() {
        String canonical = PuduRequestSigner.canonicalPath(
                "/pudu-entry/data-open-platform-service/v1/api/healthCheck",
                "b=2&a=%23%23%23Special%20Character%20Test&c=3");
        assertThat(canonical).isEqualTo(
                "/pudu-entry/data-open-platform-service/v1/api/healthCheck?a=###Special Character Test&b=2&c=3");

        PuduRequestSigner.Signature s = SIGNER.sign("GET", URI.create(
                "https://css-open-platform.pudutech.com/pudu-entry/data-open-platform-service/v1/api/healthCheck"
                        + "?b=2&a=%23%23%23Special%20Character%20Test&c=3"),
                null, AT);
        assertThat(s.authorization()).endsWith("signature=\"PFRLVW66mnXI0gFPbZeaujQLKY8=\"");
    }

    /** Only the environment prefix is stripped, never the service prefix. */
    @Test
    void environmentPrefixIsStripped() {
        assertThat(PuduRequestSigner.canonicalPath("/release/pudu-entry/x/y", "k=v"))
                .isEqualTo("/pudu-entry/x/y?k=v");
        assertThat(PuduRequestSigner.canonicalPath("/test/pudu-entry/x", null))
                .isEqualTo("/pudu-entry/x");
        assertThat(PuduRequestSigner.canonicalPath("/prepub", ""))
                .isEqualTo("/");

        PuduRequestSigner.Signature s = SIGNER.sign("GET",
                URI.create("https://css-open-platform.pudutech.com/release/pudu-entry/x/y?k=v"), null, AT);
        assertThat(s.authorization()).endsWith("signature=\"xap2fNe80EEvQO6AFvZzfH8CfRg=\"");
    }

    /** Repeated keys join with a comma; a key with only an empty value is written bare. */
    @Test
    void repeatedAndEmptyValues() {
        assertThat(PuduRequestSigner.canonicalPath("/pudu-entry/p", "z=&a=1&a=2"))
                .isEqualTo("/pudu-entry/p?a=1,2&z");

        PuduRequestSigner.Signature s = SIGNER.sign("GET",
                URI.create("https://h/pudu-entry/p?z=&a=1&a=2"), null, AT);
        assertThat(s.authorization()).endsWith("signature=\"zydGxuu5mLf1C7s9DdKAF3RNbHQ=\"");
    }

    @Test
    void theDateIsZeroPaddedGmt() {
        PuduRequestSigner.Signature s = SIGNER.sign("GET", URI.create("https://h/pudu-entry/p"), null,
                Instant.parse("2026-01-02T09:05:07Z"));
        assertThat(s.xDate()).isEqualTo("Fri, 02 Jan 2026 09:05:07 GMT");
    }

    /** The reference's odd Content-MD5: base64 of the hex digest, and only for POST. */
    @Test
    void contentMd5IsBase64OfTheHexDigestAndOnlyForPost() {
        // md5("") = d41d8cd98f00b204e9800998ecf8427e
        assertThat(PuduRequestSigner.contentMd5(""))
                .isEqualTo("ZDQxZDhjZDk4ZjAwYjIwNGU5ODAwOTk4ZWNmODQyN2U=");
        assertThat(PuduRequestSigner.signingString(X_DATE, "GET", "/pudu-entry/p", ""))
                .isEqualTo("x-date: " + X_DATE + "\nGET\napplication/json\napplication/json\n\n/pudu-entry/p");
    }
}
