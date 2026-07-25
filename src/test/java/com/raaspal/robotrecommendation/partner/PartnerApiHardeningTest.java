package com.raaspal.robotrecommendation.partner;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.partner.entity.Partner;
import com.raaspal.robotrecommendation.partner.entity.PartnerApiKey;
import com.raaspal.robotrecommendation.partner.repository.PartnerApiAccessLogRepository;
import com.raaspal.robotrecommendation.partner.repository.PartnerApiKeyRepository;
import com.raaspal.robotrecommendation.partner.repository.PartnerRepository;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService.GeneratedKey;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production hardening of the partner API: key expiry, per-key rate limiting,
 * the access audit trail, and strict {@code month} validation.
 *
 * <p>The rate limit is set to 3/minute here so the throttle can be proven in a
 * handful of requests rather than 120.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "app.partner.rate-limit-enabled=true",
        "app.partner.rate-limit-per-minute=3",
        "app.partner.audit-enabled=true",
})
class PartnerApiHardeningTest {

    private static final String ME = "/api/partner/v1/me";
    private static final String HEADER = "X-API-Key";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PartnerRepository partnerRepository;
    @Autowired
    private PartnerApiKeyRepository partnerApiKeyRepository;
    @Autowired
    private PartnerApiKeyService partnerApiKeyService;
    @Autowired
    private PartnerApiAccessLogRepository accessLogRepository;
    @Autowired
    private CustomerProfileRepository customerProfileRepository;
    @Autowired
    private RobotUnitRepository robotUnitRepository;
    @Autowired
    private DeploymentRepository deploymentRepository;

    private Partner partner;
    private String apiKey;

    @BeforeEach
    void setUp() {
        partner = partnerRepository.save(
                Partner.builder().name("Hardening " + System.nanoTime()).isActive(true).build());
        apiKey = partnerApiKeyService.generate(partner.getId(), "hardening", null).apiKey();
    }

    /* ── Key expiry ───────────────────────────────────────────────────────── */

    @Test
    void keyWithFutureExpiryStillWorks() throws Exception {
        String key = partnerApiKeyService.generate(partner.getId(), "30 days", 30).apiKey();

        mockMvc.perform(get(ME).header(HEADER, key)).andExpect(status().isOk());
    }

    @Test
    void expiredKeyIsRejected() throws Exception {
        GeneratedKey generated = partnerApiKeyService.generate(partner.getId(), "expiring", 1);
        // Backdate past its expiry — the same state a key reaches on its own.
        PartnerApiKey stored = partnerApiKeyRepository.findById(generated.id()).orElseThrow();
        stored.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        partnerApiKeyRepository.save(stored);

        mockMvc.perform(get(ME).header(HEADER, generated.apiKey()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mintedKeyReportsItsExpiry() {
        GeneratedKey withExpiry = partnerApiKeyService.generate(partner.getId(), "temp", 7);
        GeneratedKey neverExpires = partnerApiKeyService.generate(partner.getId(), "permanent", null);

        assertThat(withExpiry.expiresAt()).isNotNull();
        assertThat(neverExpires.expiresAt()).isNull();
    }

    @Test
    void expiryMustBeAtLeastOneDay() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> partnerApiKeyService.generate(partner.getId(), "bad", 0))
                .hasMessageContaining("at least 1");
    }

    /* ── Rate limiting ────────────────────────────────────────────────────── */

    @Test
    void requestsWithinTheLimitAreServedAndAdvertiseTheBudget() throws Exception {
        mockMvc.perform(get(ME).header(HEADER, apiKey))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "2"));
    }

    @Test
    void exceedingTheLimitReturns429WithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get(ME).header(HEADER, apiKey)).andExpect(status().isOk());
        }
        // The 4th request in the same minute is over the 3/min budget.
        mockMvc.perform(get(ME).header(HEADER, apiKey))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.success").value(false));
    }

    /** The budget is per key/partner, so one partner cannot throttle another. */
    @Test
    void rateLimitIsScopedToTheCallingPartner() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(get(ME).header(HEADER, apiKey));
        }
        Partner other = partnerRepository.save(
                Partner.builder().name("Other " + System.nanoTime()).isActive(true).build());
        String othersKey = partnerApiKeyService.generate(other.getId(), "other", null).apiKey();

        mockMvc.perform(get(ME).header(HEADER, othersKey)).andExpect(status().isOk());
    }

    /* ── Access audit ─────────────────────────────────────────────────────── */

    @Test
    void successfulRequestIsAudited() throws Exception {
        mockMvc.perform(get(ME).header(HEADER, apiKey)).andExpect(status().isOk());

        assertThat(accessLogRepository.findAll())
                .anySatisfy(entry -> {
                    assertThat(entry.getPartnerId()).isEqualTo(partner.getId());
                    assertThat(entry.getPath()).isEqualTo(ME);
                    assertThat(entry.getStatus()).isEqualTo(200);
                    assertThat(entry.getMethod()).isEqualTo("GET");
                    assertThat(entry.getApiKeyId()).isNotNull();
                });
    }

    /** Rejected attempts are the ones worth recording — with no partner attached. */
    @Test
    void rejectedRequestIsAuditedWithoutAPartner() throws Exception {
        mockMvc.perform(get(ME).header(HEADER, "pk_bogus")).andExpect(status().isUnauthorized());

        assertThat(accessLogRepository.findAll())
                .anySatisfy(entry -> {
                    assertThat(entry.getPartnerId()).isNull();
                    assertThat(entry.getStatus()).isEqualTo(401);
                });
    }

    /** "Who fetched what" means the filters matter, not just the path. */
    @Test
    void auditRecordsWhichDataWasRequested() throws Exception {
        String serial = ownedRobotSerial();
        mockMvc.perform(get("/api/partner/v1/robots/" + serial + "/task-reports?month=2026-07")
                        .header(HEADER, apiKey))
                .andExpect(status().isOk());

        assertThat(accessLogRepository.findAll())
                .anySatisfy(entry -> {
                    assertThat(entry.getPath()).contains(serial);
                    assertThat(entry.getQueryString()).isEqualTo("month=2026-07");
                });
    }

    /* ── month validation ─────────────────────────────────────────────────── */

    /**
     * A typo used to match no rows and read as "this robot did nothing that
     * month" — a silent wrong answer. It must be an explicit 400.
     */
    @Test
    void malformedMonthIsRejectedRatherThanSilentlyEmpty() throws Exception {
        String serial = ownedRobotSerial();

        mockMvc.perform(get("/api/partner/v1/robots/" + serial + "/task-reports")
                        .param("month", "2026-7") // not zero-padded
                        .header(HEADER, apiKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("YYYY-MM")));
    }

    @Test
    void nonNumericMonthIsRejected() throws Exception {
        String serial = ownedRobotSerial();

        mockMvc.perform(get("/api/partner/v1/robots/" + serial + "/task-reports")
                        .param("month", "July")
                        .header(HEADER, apiKey))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wellFormedMonthIsAccepted() throws Exception {
        String serial = ownedRobotSerial();

        mockMvc.perform(get("/api/partner/v1/robots/" + serial + "/task-reports")
                        .param("month", "2026-07")
                        .header(HEADER, apiKey))
                .andExpect(status().isOk());
    }

    /** Registers a robot deployed to a customer and serviced by this partner. */
    private String ownedRobotSerial() {
        String serial = "SN-HARDEN-" + System.nanoTime();
        CustomerProfile customer = customerProfileRepository.save(
                CustomerProfile.builder().companyName("Cust " + System.nanoTime()).build());
        RobotUnit robot = robotUnitRepository.save(RobotUnit.builder()
                .serialNumber(serial)
                .brand("GAUSIUM")
                .build());
        deploymentRepository.save(Deployment.builder()
                .robotUnit(robot)
                .customerProfile(customer)
                .partnerId(partner.getId())
                .isActive(true)
                .deployedAt(LocalDateTime.now())
                .build());
        return serial;
    }
}
