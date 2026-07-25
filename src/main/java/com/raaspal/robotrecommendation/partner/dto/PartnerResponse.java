package com.raaspal.robotrecommendation.partner.dto;

import com.raaspal.robotrecommendation.partner.entity.Partner;

import java.time.LocalDateTime;
import java.util.UUID;

/** A partner as seen by the admin console. Carries no keys. */
public record PartnerResponse(
        UUID id,
        String name,
        boolean active,
        LocalDateTime createdAt) {

    public static PartnerResponse of(Partner partner) {
        return new PartnerResponse(
                partner.getId(),
                partner.getName(),
                Boolean.TRUE.equals(partner.getIsActive()),
                partner.getCreatedAt());
    }
}
