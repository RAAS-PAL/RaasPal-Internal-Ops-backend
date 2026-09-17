package com.raaspal.robotrecommendation.robotunit.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contracts ending soon and contracts already ended, for the console and the alert.
 *
 * @param asOf       the business-zone date the lists were computed for
 * @param windowDays how far ahead "ending soon" looks
 */
public record ContractExpiryResponse(
        LocalDate asOf,
        int windowDays,
        List<Contract> endingSoon,
        List<Contract> ended) {

    /** Where a deployment's contract stands relative to today. */
    public enum Status { NONE, ACTIVE, ENDING_SOON, ENDED }

    /**
     * @param daysToEnd negative once ended
     * @param alertedAt when the ending-soon alert was emailed; null if not yet
     * @param document  the contract PDF attached to this deployment; null if none
     */
    public record Contract(
            UUID robotUnitId,
            String serialNumber,
            String name,
            String brand,
            String model,
            UUID customerProfileId,
            String customerName,
            String site,
            LocalDate contractStartDate,
            LocalDate contractEndDate,
            long daysToEnd,
            Status status,
            Instant alertedAt,
            ContractDocumentInfo document) {
    }
}
