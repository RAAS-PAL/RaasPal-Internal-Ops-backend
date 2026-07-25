package com.raaspal.robotrecommendation.partner.dto;

import java.util.UUID;

/**
 * Assign a deployment (a robot at a customer site) to the partner that services
 * it. A {@code null} {@code partnerId} un-assigns the deployment (back to
 * RAASPAL-direct). This is what scopes a partner's view of the data.
 */
public record AssignPartnerRequest(UUID partnerId) {
}
