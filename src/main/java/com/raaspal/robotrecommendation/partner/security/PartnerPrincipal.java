package com.raaspal.robotrecommendation.partner.security;

import java.util.UUID;

/**
 * The authenticated caller on the partner API — set by {@link ApiKeyAuthFilter}
 * as the security principal and read by partner controllers to scope every query
 * to this partner (never a request parameter).
 *
 * <p>{@code apiKeyId} identifies <em>which</em> of the partner's keys was used, so
 * {@link PartnerAccessAuditFilter} can attribute a request to a single key — the
 * difference between "PCS called us" and "PCS's retired server key is still
 * calling us".
 */
public record PartnerPrincipal(UUID partnerId, String partnerName, UUID apiKeyId) {
}
