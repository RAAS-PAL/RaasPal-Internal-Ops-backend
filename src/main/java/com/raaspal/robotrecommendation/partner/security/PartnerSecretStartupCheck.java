package com.raaspal.robotrecommendation.partner.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Refuses to start the application when the partner token signing secret is still
 * the placeholder committed to {@code application.properties}.
 *
 * <p><strong>Why this is fatal rather than a warning.</strong> The partner bearer
 * token is signed with {@code app.partner.jwt.secret} and carries the partner id
 * that every data query is scoped to. Anyone holding that secret can mint a token
 * naming any partner and read that partner's fleet — no credential, no client id,
 * nothing revocable. The placeholder is in the repository, so shipping it means the
 * signing key is public: not weak, published. A warning in a startup log is not
 * proportionate to that, and startup logs are exactly what nobody reads on a
 * successful deploy.
 *
 * <p>Failing to boot is safe to do here in a way it usually is not. A missing
 * environment variable becomes an obvious deploy failure naming the variable,
 * rather than a service that comes up healthy and serves one partner's data to
 * anybody who asks.
 *
 * <p>Local development sets {@code app.security.allow-placeholder-secrets=true} in
 * {@code application-local.properties}, alongside the other credentials that only
 * exist there. Deliberately an explicit opt-in rather than a profile check: which
 * profile is active depends on how the process happens to be launched, whereas this
 * flag has to be written down somewhere on purpose.
 */
@Slf4j
@Component
public class PartnerSecretStartupCheck {

    /**
     * Substrings that mark a value as a stand-in rather than a secret. Matching on
     * intent rather than on the exact literal means editing the placeholder in
     * {@code application.properties} — or adding another in the same spirit — does
     * not quietly disable this check. A generated key is random and will not contain
     * these words.
     */
    private static final String[] PLACEHOLDER_MARKERS = {"change-me", "change-this", "dev-secret"};

    private final String partnerSecret;
    private final String staffSecret;
    private final boolean allowPlaceholders;

    public PartnerSecretStartupCheck(
            @Value("${app.partner.jwt.secret}") String partnerSecret,
            @Value("${app.jwt.secret}") String staffSecret,
            @Value("${app.security.allow-placeholder-secrets:false}") boolean allowPlaceholders) {
        this.partnerSecret = partnerSecret;
        this.staffSecret = staffSecret;
        this.allowPlaceholders = allowPlaceholders;
    }

    @PostConstruct
    public void verify() {
        if (allowPlaceholders) {
            if (isPlaceholder(partnerSecret)) {
                log.warn("Partner token secret is a placeholder. Permitted because "
                        + "app.security.allow-placeholder-secrets=true — never set that in production.");
            }
            return;
        }

        if (isPlaceholder(partnerSecret)) {
            throw new IllegalStateException("""
                    PARTNER_JWT_SECRET is not set, so partner tokens would be signed with the \
                    placeholder committed to application.properties. That key is public, and \
                    anyone holding it can mint a token for any partner and read that partner's \
                    data. Set PARTNER_JWT_SECRET to a random value of at least 32 characters \
                    (it must also differ from JWT_SECRET, so a partner token and a staff token \
                    can never be interchanged).""");
        }

        // The staff secret is the same class of mistake, but this service predates the
        // partner API and is already deployed. Failing its boot from here would be an
        // outage rather than a fix, so it is reported for someone to act on instead.
        if (isPlaceholder(staffSecret)) {
            log.error("JWT_SECRET is not set: staff tokens are signed with the placeholder "
                    + "committed to application.properties. Set it in the deployment environment.");
        }
    }

    private static boolean isPlaceholder(String secret) {
        if (secret == null || secret.isBlank()) {
            return true;
        }
        String lower = secret.toLowerCase(Locale.ROOT);
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
