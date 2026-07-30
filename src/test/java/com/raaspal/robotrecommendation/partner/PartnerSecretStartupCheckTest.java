package com.raaspal.robotrecommendation.partner;

import com.raaspal.robotrecommendation.partner.security.PartnerSecretStartupCheck;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A deploy that forgets {@code PARTNER_JWT_SECRET} must not come up healthy. The
 * placeholder is in the repository, so running with it means the key that signs
 * every partner token — the token carrying the partner id all data is scoped to —
 * is public.
 */
class PartnerSecretStartupCheckTest {

    private static final String COMMITTED_PLACEHOLDER = "partner-dev-secret-change-me-minimum-32-characters!!";
    private static final String REAL = "9f3c1e7ba4d84a0fbd6e2c5a8471be03";

    @Test
    void theCommittedPlaceholderStopsTheApplicationStarting() {
        assertThatThrownBy(() -> check(COMMITTED_PLACEHOLDER, REAL, false).verify())
                .isInstanceOf(IllegalStateException.class)
                // The message has to name the variable to set: whoever sees this is
                // reading a failed deploy log, not this class.
                .hasMessageContaining("PARTNER_JWT_SECRET");
    }

    @Test
    void aConfiguredSecretStartsNormally() {
        assertThatCode(() -> check(REAL, REAL, false).verify()).doesNotThrowAnyException();
    }

    /** Local development opts in explicitly, so it needs no extra environment. */
    @Test
    void theOptOutIsHonouredForLocalDevelopment() {
        assertThatCode(() -> check(COMMITTED_PLACEHOLDER, COMMITTED_PLACEHOLDER, true).verify())
                .doesNotThrowAnyException();
    }

    /**
     * The check matches on placeholder intent rather than the exact committed
     * literal, so editing or renaming the placeholder cannot quietly disable it.
     */
    @Test
    void anyValueMarkedAsAStandInIsRejected() {
        assertThatThrownBy(() -> check("some-other-change-me-value-32-characters", REAL, false).verify())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> check("CHANGE-THIS-to-a-real-secret-min-32-chars", REAL, false).verify())
                .isInstanceOf(IllegalStateException.class);
    }

    /** An unset variable resolving to empty is the same mistake, not a valid key. */
    @Test
    void aBlankSecretIsRejected() {
        assertThatThrownBy(() -> check("   ", REAL, false).verify())
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * A placeholder staff secret is reported but does not stop startup: that service
     * is already deployed, so failing its boot from here would cause an outage rather
     * than prevent one.
     */
    @Test
    void aPlaceholderStaffSecretDoesNotBlockStartup() {
        assertThatCode(() -> check(REAL, COMMITTED_PLACEHOLDER, false).verify())
                .doesNotThrowAnyException();
    }

    private static PartnerSecretStartupCheck check(String partner, String staff, boolean allow) {
        return new PartnerSecretStartupCheck(partner, staff, allow);
    }
}
