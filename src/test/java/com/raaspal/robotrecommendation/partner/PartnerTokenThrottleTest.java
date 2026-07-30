package com.raaspal.robotrecommendation.partner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The token endpoint is {@code permitAll} — the one partner route reachable
 * without a credential. Every other route is metered per authenticated partner,
 * which by definition cannot protect this one: there is no identity to meter until
 * the exchange has already happened.
 *
 * <p>What that leaves unprotected is not the secret. A client secret is 256 bits of
 * random, so no request rate makes guessing it feasible. It is the work each
 * anonymous request buys: a credential lookup and an audit insert, against a free
 * Supabase tier, from an unauthenticated loop that could fill the audit table
 * indefinitely.
 *
 * <p>Runs in its own context with a small budget, so the exchange tests elsewhere
 * are unaffected by it and it is unaffected by them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.partner.token-rate-limit-per-minute=5")
class PartnerTokenThrottleTest {

    private static final String TOKEN_URL = "/api/partner/v1/oauth/token";
    private static final int LIMIT = 5;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anUnauthenticatedLoopIsThrottledRatherThanServedForever() throws Exception {
        String ip = "203.0.113.10";

        for (int i = 1; i <= LIMIT; i++) {
            mockMvc.perform(attempt(ip))
                    .andExpect(status().isUnauthorized())          // bad credentials, but served
                    .andExpect(header().string("X-RateLimit-Limit", String.valueOf(LIMIT)));
        }

        mockMvc.perform(attempt(ip))
                .andExpect(status().isTooManyRequests())
                // A caller told to back off needs to know for how long.
                .andExpect(header().exists("Retry-After"));
    }

    /**
     * The throttle is only worth having if its subject cannot be chosen by the
     * caller. {@code X-Forwarded-For} is a trail each proxy appends to, so the
     * <em>leftmost</em> entry is whatever the caller claimed — reading it would let
     * an attacker reset their own budget by varying a header, one forged value per
     * request, forever.
     *
     * <p>These requests carry a different forged leading value each time and the
     * same trailing value, which is the shape Render produces when a client sends
     * its own header. They must all land in one budget.
     */
    @Test
    void aForgedXForwardedForCannotResetTheBudget() throws Exception {
        String realAddress = "203.0.113.11";

        for (int i = 1; i <= LIMIT; i++) {
            mockMvc.perform(attempt("198.51.100." + i, realAddress))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(attempt("198.51.100.99", realAddress))
                .andExpect(status().isTooManyRequests());
    }

    /** One caller exhausting its budget must not lock out an unrelated caller. */
    @Test
    void budgetsAreHeldPerAddress() throws Exception {
        String noisy = "203.0.113.12";
        for (int i = 1; i <= LIMIT + 1; i++) {
            mockMvc.perform(attempt(noisy));
        }
        mockMvc.perform(attempt(noisy)).andExpect(status().isTooManyRequests());

        mockMvc.perform(attempt("203.0.113.13"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(LIMIT - 1)));
    }

    /* ── helpers ──────────────────────────────────────────────────────────── */

    /** A well-formed exchange with credentials that do not exist. */
    private MockHttpServletRequestBuilder exchange() {
        return post(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
                .param("client_id", "cid_does-not-exist")
                .param("client_secret", "pk_does-not-exist");
    }

    /** Arriving straight from {@code ip}, with no proxy header. */
    private MockHttpServletRequestBuilder attempt(String ip) {
        return exchange().with(remoteAddress(ip));
    }

    /** Arriving via a proxy that appended {@code realAddress} after the caller's claim. */
    private MockHttpServletRequestBuilder attempt(String claimed, String realAddress) {
        return exchange()
                .header("X-Forwarded-For", claimed + ", " + realAddress)
                .with(remoteAddress("10.0.0.1"));
    }

    private static RequestPostProcessor remoteAddress(String ip) {
        return request -> {
            ((MockHttpServletRequest) request).setRemoteAddr(ip);
            return request;
        };
    }
}
