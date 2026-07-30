package com.raaspal.robotrecommendation.partner.security;

import java.util.UUID;

/**
 * The authenticated caller on the partner API — set by {@link PartnerJwtAuthFilter}
 * as the security principal and read by partner controllers to scope every query
 * to this partner (never a request parameter).
 *
 * <p>{@code apiKeyId} identifies <em>which</em> of the partner's credentials was
 * used, so {@link PartnerAccessAuditFilter} can attribute a request to a single
 * credential — the difference between "PCS called us" and "PCS's retired server
 * credential is still calling us".
 */
public record PartnerPrincipal(UUID partnerId, String partnerName, UUID apiKeyId) {

    /**
     * Request attribute under which the authenticating filter publishes the
     * principal. {@link PartnerAccessAuditFilter} reads this rather than the
     * SecurityContext because it runs outermost, and Spring Security clears the
     * context on the way out — a request attribute lives for the whole request.
     */
    public static final String REQUEST_ATTRIBUTE = "raaspal.partnerPrincipal";
}
