package com.raaspal.robotrecommendation.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.partner.entity.Partner;
import com.raaspal.robotrecommendation.partner.entity.PartnerApiKey;
import com.raaspal.robotrecommendation.partner.repository.PartnerApiKeyRepository;
import com.raaspal.robotrecommendation.partner.repository.PartnerRepository;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService.GeneratedKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OAuth 2.0 client credentials grant end to end: exchanging a client id and
 * secret for a bearer, then using that bearer on the data API.
 *
 * <p>Every rejection must look identical from outside. If an unknown client id
 * were distinguishable from a wrong secret, the endpoint would let an attacker
 * enumerate valid client ids.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PartnerOAuthTest {

    private static final String TOKEN_URL = "/api/partner/v1/oauth/token";
    private static final String ME = "/api/partner/v1/me";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PartnerRepository partnerRepository;
    @Autowired
    private PartnerApiKeyRepository partnerApiKeyRepository;
    @Autowired
    private PartnerApiKeyService partnerApiKeyService;
    @Autowired
    private ObjectMapper objectMapper;

    private Partner partner;
    private GeneratedKey credential;

    @BeforeEach
    void setUp() {
        partner = partnerRepository.save(
                Partner.builder().name("OAuth " + System.nanoTime()).isActive(true).build());
        credential = partnerApiKeyService.generate(partner.getId(), "oauth test", 90);
    }

    /* ── Issuing ──────────────────────────────────────────────────────────── */

    @Test
    void formEncodedExchangeReturnsASpecCompliantToken() throws Exception {
        mockMvc.perform(post(TOKEN_URL)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", credential.clientId())
                        .param("client_secret", credential.apiKey()))
                .andExpect(status().isOk())
                // snake_case per RFC 6749 — off-the-shelf clients look for exactly these.
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(3600));
    }

    @Test
    void jsonExchangeAlsoWorks() throws Exception {
        mockMvc.perform(post(TOKEN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "grant_type", "client_credentials",
                                "client_id", credential.clientId(),
                                "client_secret", credential.apiKey()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty());
    }

    @Test
    void httpBasicCredentialsAreAccepted() throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (credential.clientId() + ":" + credential.apiKey()).getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post(TOKEN_URL)
                        .header("Authorization", "Basic " + basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty());
    }

    /** The token endpoint must be reachable without already holding a token. */
    @Test
    void tokenEndpointDoesNotItselfRequireAuthentication() throws Exception {
        mockMvc.perform(post(TOKEN_URL)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "cid_nope")
                        .param("client_secret", "wrong"))
                // 401 invalid_client, not the chain's generic "no bearer" rejection.
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    /* ── Using the token ──────────────────────────────────────────────────── */

    @Test
    void issuedTokenAuthenticatesTheDataApi() throws Exception {
        String token = exchange();

        mockMvc.perform(get(ME).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partnerId").value(partner.getId().toString()));
    }

    /* ── Rejections — all indistinguishable ───────────────────────────────── */

    @Test
    void wrongSecretIsRejected() throws Exception {
        expectInvalidClient(credential.clientId(), "pk_completely-wrong-secret");
    }

    @Test
    void unknownClientIdIsRejected() throws Exception {
        expectInvalidClient("cid_does-not-exist", credential.apiKey());
    }

    @Test
    void revokedCredentialCannotObtainAToken() throws Exception {
        partnerApiKeyService.revoke(credential.id());

        expectInvalidClient(credential.clientId(), credential.apiKey());
    }

    @Test
    void expiredCredentialCannotObtainAToken() throws Exception {
        PartnerApiKey stored = partnerApiKeyRepository.findById(credential.id()).orElseThrow();
        stored.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        partnerApiKeyRepository.save(stored);

        expectInvalidClient(credential.clientId(), credential.apiKey());
    }

    @Test
    void disabledPartnerCannotObtainAToken() throws Exception {
        partner.setIsActive(false);
        partnerRepository.save(partner);

        expectInvalidClient(credential.clientId(), credential.apiKey());
    }

    /**
     * No credentials at all is a different failure from wrong credentials, and is
     * reported as such. In practice it means the body could not be read — almost
     * always a Content-Type matching neither form-encoding nor JSON. Saying
     * "authentication failed" there sends the caller hunting in the wrong place,
     * and it leaks nothing to be precise, since the request named no client.
     */
    @Test
    void missingCredentialsReportInvalidRequestNotInvalidClient() throws Exception {
        mockMvc.perform(post(TOKEN_URL)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.error_description")
                        .value(org.hamcrest.Matchers.containsString("client_id and client_secret are required")));
    }

    /** A body sent with an unreadable content type must say so, not blame the credentials. */
    @Test
    void unreadableBodyReportsInvalidRequest() throws Exception {
        mockMvc.perform(post(TOKEN_URL)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"client_id\":\"cid_x\",\"client_secret\":\"pk_y\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void unsupportedGrantTypeIsNamedAsSuch() throws Exception {
        mockMvc.perform(post(TOKEN_URL)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "password")
                        .param("client_id", credential.clientId())
                        .param("client_secret", credential.apiKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported_grant_type"));
    }

    /* ── The minted credential ────────────────────────────────────────────── */

    @Test
    void mintingProducesAClientIdDistinctFromTheSecret() {
        assertThat(credential.clientId()).startsWith("cid_");
        assertThat(credential.apiKey()).startsWith("pk_");
        // The public id must not be derivable from, or a prefix of, the secret.
        assertThat(credential.apiKey()).doesNotContain(credential.clientId());
        assertThat(credential.clientId()).isNotEqualTo(credential.keyPrefix());
    }

    /* ── helpers ──────────────────────────────────────────────────────────── */

    private String exchange() throws Exception {
        String body = mockMvc.perform(post(TOKEN_URL)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", credential.clientId())
                        .param("client_secret", credential.apiKey()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("access_token").asText();
    }

    /** Every failure mode must produce this same response. */
    private void expectInvalidClient(String clientId, String clientSecret) throws Exception {
        var request = post(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials");
        if (clientId != null) {
            request = request.param("client_id", clientId);
        }
        if (clientSecret != null) {
            request = request.param("client_secret", clientSecret);
        }
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"))
                .andExpect(jsonPath("$.error_description").value("Client authentication failed"));
    }
}
