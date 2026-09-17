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
     * Every active deployment as a contract row, for the Contracts page's "All" view.
     * Status is computed against {@code windowDays}, so the page's filter chips and
     * the ending-soon list agree on what "soon" means.
     */
    public record All(LocalDate asOf, int windowDays, List<Contract> contracts) {
    }

    /**
     * @param daysToEnd negative once ended; null when there is no end date
     * @param alertedAt when the ending-soon alert was emailed; null if not yet
     * @param document  the contract PDF attached to this deployment; null if none
     * @param followup  what the CS team has done about renewing; never null
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
            Long daysToEnd,
            Status status,
            Instant alertedAt,
            ContractDocumentInfo document,
            ContractRenewalFollowup followup) {
    }
}
