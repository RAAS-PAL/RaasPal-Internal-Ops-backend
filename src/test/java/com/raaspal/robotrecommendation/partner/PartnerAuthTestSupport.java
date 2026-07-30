package com.raaspal.robotrecommendation.partner;

import com.raaspal.robotrecommendation.partner.entity.Partner;
import com.raaspal.robotrecommendation.partner.security.PartnerTokenService;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService.GeneratedKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Mints partner credentials and turns them into bearer tokens for tests.
 *
 * <p>Most partner tests care about scoping, rate limiting or auditing rather
 * than the token endpoint itself, so they take a shortcut: issue the token
 * directly through {@link PartnerTokenService} instead of performing the HTTP
 * exchange. The exchange is covered end to end by {@code PartnerOAuthTest}.
 */
@Component
public class PartnerAuthTestSupport {

    @Autowired
    private PartnerApiKeyService partnerApiKeyService;
    @Autowired
    private PartnerTokenService partnerTokenService;

    /** A credential for the partner, plus a bearer header value that authenticates as it. */
    public record Credential(GeneratedKey key, String authorizationHeader) {
    }

    public Credential credentialFor(Partner partner) {
        return credentialFor(partner, null);
    }

    public Credential credentialFor(Partner partner, Integer expiresInDays) {
        GeneratedKey key = partnerApiKeyService.generate(partner.getId(), "test", expiresInDays);
        return new Credential(key, bearerFor(partner, key));
    }

    /** The `Authorization` header value for an already-minted credential. */
    public String bearerFor(Partner partner, GeneratedKey key) {
        return "Bearer " + partnerTokenService.issue(partner.getId(), partner.getName(), key.id());
    }
}
