package com.raaspal.robotrecommendation.customer.dto;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;

import java.time.LocalDateTime;
import java.util.UUID;

/** A customer record plus how many robots are currently deployed to it. */
public record CustomerResponse(
        UUID id,
        String companyName,
        String industry,
        String contactEmail,
        String contactPhone,
        String address,
        String notes,
        long robotCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static CustomerResponse of(CustomerProfile c, long robotCount) {
        return new CustomerResponse(
                c.getId(),
                c.getCompanyName(),
                c.getIndustry(),
                c.getContactEmail(),
                c.getContactPhone(),
                c.getAddress(),
                c.getNotes(),
                robotCount,
                c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
