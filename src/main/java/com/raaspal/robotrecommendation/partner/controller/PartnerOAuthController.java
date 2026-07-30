package com.raaspal.robotrecommendation.partner.controller;

import com.raaspal.robotrecommendation.partner.dto.TokenResponse;
import com.raaspal.robotrecommendation.partner.security.PartnerTokenService;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService.AuthenticatedPartner;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * The OAuth 2.0 token endpoint for the partner API — the <em>client credentials
 * grant</em> (RFC 6749 §4.4).
 *
 * <p>A partner exchanges its {@code client_id} and {@code client_secret} for a
 * short-lived bearer token, then sends that token on data requests. The
 * long-lived secret therefore travels once per hour instead of on every call,
 * and a leaked bearer expires by itself.
 *
 * <p>Credentials are accepted three ways, so both hand-rolled clients and
 * standard OAuth libraries work:
 * <ol>
 *   <li>{@code application/x-www-form-urlencoded} body — what the specification mandates</li>
 *   <li>JSON body — a convenience, and the style Gausium's own token endpoint uses</li>
 *   <li>HTTP Basic {@code Authorization} header — also specification-sanctioned</li>
 * </ol>
 *
 * <p>Every failure returns the same {@code invalid_client} error. Distinguishing
 * "unknown client" from "wrong secret" would let an attacker enumerate valid
 * client ids.
 */
@Slf4j
@RestController
@RequestMapping("/api/partner/v1/oauth")
@RequiredArgsConstructor
public class PartnerOAuthController {

    private static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";

    private final PartnerApiKeyService partnerApiKeyService;
    private final PartnerTokenService partnerTokenService;

    /** Form-encoded — the specification's required form. */
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> tokenFromForm(
            @RequestParam(name = "grant_type", required = false) String grantType,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "client_secret", required = false) String clientSecret,
            HttpServletRequest request) {
        return issue(grantType, clientId, clientSecret, request);
    }

    /** JSON — convenience, and what Gausium's own token endpoint accepts. */
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> tokenFromJson(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        Map<String, String> safe = body == null ? Map.of() : body;
        return issue(safe.get("grant_type"), safe.get("client_id"), safe.get("client_secret"), request);
    }

    /** Credentials supplied only via HTTP Basic, with no body at all. */
    @PostMapping("/token")
    public ResponseEntity<?> tokenFromBasicAuth(HttpServletRequest request) {
        return issue(GRANT_TYPE_CLIENT_CREDENTIALS, null, null, request);
    }

    private ResponseEntity<?> issue(String grantType, String clientId, String clientSecret,
                                    HttpServletRequest request) {
        // Basic auth fills in whatever the body did not supply.
        BasicCredentials basic = parseBasicAuth(request.getHeader("Authorization"));
        if (basic != null) {
            if (!StringUtils.hasText(clientId)) {
                clientId = basic.clientId();
            }
            if (!StringUtils.hasText(clientSecret)) {
                clientSecret = basic.clientSecret();
            }
        }

        // A missing grant_type is tolerated (this endpoint supports exactly one),
        // but a *different* one must be named as unsupported per the spec.
        if (StringUtils.hasText(grantType) && !GRANT_TYPE_CLIENT_CREDENTIALS.equals(grantType)) {
            return oauthError(HttpStatus.BAD_REQUEST, "unsupported_grant_type",
                    "Only client_credentials is supported");
        }

        // No credentials reached us at all — which usually means the body could not
        // be read, not that the credentials are wrong. Nearly always a Content-Type
        // that matches neither application/x-www-form-urlencoded nor
        // application/json, so the body is ignored entirely.
        //
        // Reported as invalid_request rather than invalid_client: a request that
        // carried no credentials reveals nothing about which client ids exist, and
        // "your credentials are wrong" would send the caller hunting in the wrong
        // place. RFC 6749 §5.2 defines invalid_request for exactly this.
        if (!StringUtils.hasText(clientId) && !StringUtils.hasText(clientSecret)) {
            log.warn("Token request carried no credentials (Content-Type: {})",
                    request.getContentType());
            return oauthError(HttpStatus.BAD_REQUEST, "invalid_request",
                    "client_id and client_secret are required. Send them as "
                            + "application/x-www-form-urlencoded or application/json, or use HTTP Basic. "
                            + "Received Content-Type: " + request.getContentType());
        }

        Optional<AuthenticatedPartner> partner =
                partnerApiKeyService.authenticateClient(clientId, clientSecret);

        if (partner.isEmpty()) {
            // Deliberately identical for unknown client, wrong secret, revoked,
            // expired, and disabled partner — so none can be told apart.
            log.warn("Token request rejected for client_id '{}'", clientId);
            return oauthError(HttpStatus.UNAUTHORIZED, "invalid_client",
                    "Client authentication failed");
        }

        AuthenticatedPartner authenticated = partner.get();
        String token = partnerTokenService.issue(
                authenticated.partnerId(), authenticated.partnerName(), authenticated.keyId());

        partnerApiKeyService.touchLastUsed(authenticated.keyId());
        log.info("Issued partner token for {} ({})",
                authenticated.partnerName(), authenticated.partnerId());

        return ResponseEntity.ok(
                TokenResponse.bearer(token, partnerTokenService.expiresInSeconds()));
    }

    /** RFC 6749 §5.2 error shape — what OAuth clients expect on failure. */
    private ResponseEntity<Map<String, String>> oauthError(HttpStatus status, String error,
                                                           String description) {
        return ResponseEntity.status(status)
                .body(Map.of("error", error, "error_description", description));
    }

    private record BasicCredentials(String clientId, String clientSecret) {
    }

    /** Decodes {@code Authorization: Basic base64(client_id:client_secret)}. */
    private BasicCredentials parseBasicAuth(String header) {
        if (!StringUtils.hasText(header) || !header.startsWith("Basic ")) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator < 0) {
                return null;
            }
            return new BasicCredentials(decoded.substring(0, separator), decoded.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            // Malformed base64 is just a failed authentication, not an error.
            return null;
        }
    }
}
