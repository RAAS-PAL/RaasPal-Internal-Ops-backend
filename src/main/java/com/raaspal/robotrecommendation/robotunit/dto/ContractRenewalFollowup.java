package com.raaspal.robotrecommendation.robotunit.dto;

import com.raaspal.robotrecommendation.robotunit.entity.ContractRenewalStatus;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;

import java.time.Instant;

/**
 * The CS follow-up on a contract row. Always present: a term nobody has touched
 * reads as {@code NOT_CONTACTED} with nothing else set.
 */
public record ContractRenewalFollowup(
        ContractRenewalStatus status,
        String note,
        String updatedBy,
        Instant updatedAt) {

    public static ContractRenewalFollowup of(Deployment d) {
        return new ContractRenewalFollowup(
                d.getRenewalStatus() == null ? ContractRenewalStatus.NOT_CONTACTED : d.getRenewalStatus(),
                d.getRenewalNote(),
                d.getRenewalUpdatedBy(),
                d.getRenewalUpdatedAt());
    }
}
