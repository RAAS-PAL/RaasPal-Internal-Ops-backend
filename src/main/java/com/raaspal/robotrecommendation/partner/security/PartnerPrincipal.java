package com.raaspal.robotrecommendation.partner.security;

import java.util.UUID;

/**
 * The authenticated caller on the partner API — set by {@link ApiKeyAuthFilter}
 * as the security principal and read by partner controllers to scope every query
 * to this partner (never a request parameter).
 */
public record PartnerPrincipal(UUID partnerId, String partnerName) {
}
