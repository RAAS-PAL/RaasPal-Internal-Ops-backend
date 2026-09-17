package com.raaspal.robotrecommendation.robotunit.dto;

import com.raaspal.robotrecommendation.robotunit.entity.ContractRenewalStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What the CS team records after (or before) calling the customer about a renewal.
 *
 * @param applyToSameContract also record it on every other robot of the same customer
 *                            with the same contract dates - one phone call covers them all
 */
public record UpdateRenewalFollowupRequest(
        @NotNull(message = "Status is required") ContractRenewalStatus status,
        @Size(max = 2000, message = "Note is limited to 2000 characters") String note,
        Boolean applyToSameContract) {

    public boolean applyToSameContractOrDefault() {
        return applyToSameContract == null || applyToSameContract;
    }
}
