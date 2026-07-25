package com.raaspal.robotrecommendation.partner;

import com.raaspal.robotrecommendation.partner.entity.Partner;
import com.raaspal.robotrecommendation.partner.repository.PartnerRepository;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService.GeneratedKey;
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
 * Authentication contract of the partner API: only a live key belonging to a
 * live partner gets in, and the partner chain stays isolated from the staff JWT
 * chain. These are the guarantees a leaked or revoked key depends on, so each
 * one is pinned by a test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PartnerApiSecurityTest {

    private static final String ME = "/api/partner/v1/me";
    private static final String HEADER = "X-API-Key";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PartnerRepository partnerRepository;
    @Autowired
    private PartnerApiKeyService partnerApiKeyService;

    private Partner partner;
    private String apiKey;

    @BeforeEach
    void setUp() {
        partner = partnerRepository.save(Partner.builder().name("PCS Test").isActive(true).build());
        GeneratedKey generated = partnerApiKeyService.generate(partner.getId(), "test key");
        apiKey = generated.apiKey();
    }

    @Test
    void validKeyIsAuthenticatedAndIdentifiesItsPartner() throws Exception {
        mockMvc.perform(get(ME).header(HEADER, apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partnerId").value(partner.getId().toString()))
                .andExpect(jsonPath("$.data.partnerName").value("PCS Test"));
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        mockMvc.perform(get(ME)).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownKeyIsRejected() throws Exception {
        mockMvc.perform(get(ME).header(HEADER, "pk_not-a-real-key"))
                .andExpect(status().isUnauthorized());
    }

    /** A blank or malformed header must be a clean 401, never a 500. */
    @Test
    void malformedKeyIsRejectedWithoutServerError() throws Exception {
        mockMvc.perform(get(ME).header(HEADER, "   ")).andExpect(status().isUnauthorized());
    }

    @Test
    void revokedKeyIsRejected() throws Exception {
        GeneratedKey doomed = partnerApiKeyService.generate(partner.getId(), "to be revoked");
        partnerApiKeyService.revoke(doomed.id());

        mockMvc.perform(get(ME).header(HEADER, doomed.apiKey()))
                .andExpect(status().isUnauthorized());
    }

    /** Disabling a partner must instantly block its still-active keys. */
    @Test
    void keyOfDisabledPartnerIsRejected() throws Exception {
        partner.setIsActive(false);
        partnerRepository.save(partner);

        mockMvc.perform(get(ME).header(HEADER, apiKey))
                .andExpect(status().isUnauthorized());
    }

    /** A partner key must not open staff endpoints — the two chains are separate. */
    @Test
    void partnerKeyCannotReachStaffApi() throws Exception {
        mockMvc.perform(get("/api/v1/robot-units").header(HEADER, apiKey))
                .andExpect(status().isUnauthorized());
    }

    /** ...and a request with no credentials at all cannot reach the partner API. */
    @Test
    void staffChainDoesNotServePartnerEndpoints() throws Exception {
        mockMvc.perform(get("/api/partner/v1/robots")).andExpect(status().isUnauthorized());
    }
}
