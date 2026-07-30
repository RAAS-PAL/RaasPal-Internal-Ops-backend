package com.raaspal.robotrecommendation.partner;

import com.raaspal.robotrecommendation.auth.security.jwt.JwtUtils;
import com.raaspal.robotrecommendation.partner.PartnerAuthTestSupport.Credential;
import com.raaspal.robotrecommendation.partner.entity.Partner;
import com.raaspal.robotrecommendation.partner.repository.PartnerRepository;
import com.raaspal.robotrecommendation.partner.security.PartnerTokenService;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authentication contract of the partner API, now that access is by bearer token
 * rather than a raw API key. Only a live token, backed by a live credential and
 * a live partner, gets in — and the partner and staff token families stay
 * mutually unusable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PartnerApiSecurityTest {

    private static final String ME = "/api/partner/v1/me";
    private static final String AUTH = "Authorization";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PartnerRepository partnerRepository;
    @Autowired
    private PartnerApiKeyService partnerApiKeyService;
    @Autowired
    private PartnerAuthTestSupport auth;
    @Autowired
    private PartnerTokenService partnerTokenService;
    @Autowired
    private JwtUtils staffJwtUtils;

    private Partner partner;
    private Credential credential;

    @BeforeEach
    void setUp() {
        partner = partnerRepository.save(Partner.builder().name("PCS Test").isActive(true).build());
        credential = auth.credentialFor(partner);
    }

    @Test
    void validTokenIsAuthenticatedAndIdentifiesItsPartner() throws Exception {
        mockMvc.perform(get(ME).header(AUTH, credential.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partnerId").value(partner.getId().toString()))
                .andExpect(jsonPath("$.data.partnerName").value("PCS Test"));
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("oauth/token")));
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        mockMvc.perform(get(ME).header(AUTH, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    /** A malformed header must be a clean 401, never a 500. */
    @Test
    void malformedAuthorizationHeaderIsRejectedWithoutServerError() throws Exception {
        mockMvc.perform(get(ME).header(AUTH, "Bearer    ")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ME).header(AUTH, "Basic abc")).andExpect(status().isUnauthorized());
    }

    /**
     * The point of re-checking the credential on every request: a signed,
     * unexpired token must stop working the moment its key is revoked, rather
     * than lingering for the rest of its hour.
     */
    @Test
    void tokenStopsWorkingImmediatelyWhenItsCredentialIsRevoked() throws Exception {
        mockMvc.perform(get(ME).header(AUTH, credential.authorizationHeader()))
                .andExpect(status().isOk());

        partnerApiKeyService.revoke(credential.key().id());

        mockMvc.perform(get(ME).header(AUTH, credential.authorizationHeader()))
                .andExpect(status().isUnauthorized());
    }

    /** Disabling a partner must instantly block tokens issued to it. */
    @Test
    void tokenOfDisabledPartnerIsRejected() throws Exception {
        partner.setIsActive(false);
        partnerRepository.save(partner);

        mockMvc.perform(get(ME).header(AUTH, credential.authorizationHeader()))
                .andExpect(status().isUnauthorized());
    }

    /** A partner token must not open staff endpoints — the two chains are separate. */
    @Test
    void partnerTokenCannotReachStaffApi() throws Exception {
        mockMvc.perform(get("/api/v1/robot-units").header(AUTH, credential.authorizationHeader()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * ...and the reverse. This is the property that used to hold only by
     * accident: {@code JwtUtils} carries no audience or type claim, so a staff
     * token was structurally acceptable to any filter sharing its secret.
     * Partner tokens now use a different signing key, so neither side can even
     * parse the other's.
     */
    @Test
    void staffTokenCannotReachPartnerApi() throws Exception {
        String staffToken = staffJwtUtils.generateToken("admin@raaspal.com");

        mockMvc.perform(get(ME).header(AUTH, "Bearer " + staffToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedRequestCannotReachPartnerData() throws Exception {
        mockMvc.perform(get("/api/partner/v1/robots")).andExpect(status().isUnauthorized());
    }

    /** The old credential is no longer accepted as a request header. */
    @Test
    void rawApiKeyNoLongerAuthenticatesDataRequests() throws Exception {
        mockMvc.perform(get(ME).header("X-API-Key", credential.key().apiKey()))
                .andExpect(status().isUnauthorized());
    }

    /** A token this service did not sign must be rejected outright. */
    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        PartnerTokenService foreign = new PartnerTokenService(
                "a-completely-different-secret-at-least-32-chars!!", 3_600_000L);
        String forged = foreign.issue(partner.getId(), partner.getName(), credential.key().id());

        mockMvc.perform(get(ME).header(AUTH, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    /** An expired token is refused even though its credential is still fine. */
    @Test
    void expiredTokenIsRejected() throws Exception {
        PartnerTokenService alreadyExpired = new PartnerTokenService(
                expectedSecret(), -1_000L); // negative TTL → issued already expired
        String stale = alreadyExpired.issue(partner.getId(), partner.getName(), credential.key().id());

        mockMvc.perform(get(ME).header(AUTH, "Bearer " + stale))
                .andExpect(status().isUnauthorized());
    }

    /** Mirrors the test-profile value of {@code app.partner.jwt.secret}. */
    private String expectedSecret() {
        return "partner-test-secret-minimum-32-characters-long!!";
    }
}
