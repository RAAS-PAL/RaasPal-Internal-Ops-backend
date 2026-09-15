package com.raaspal.robotrecommendation.telemetry.dto;

import com.raaspal.robotrecommendation.telemetry.entity.ZeroDataFollowup;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The robots that should have reported a month and logged nothing, as a worklist.
 *
 * @param month      the month asked for, {@code YYYY-MM}
 * @param monthLabel as the report names it, "July 2026"
 * @param inScope    active deployments whose contract overlaps the month
 * @param zeroData   how many of those logged no task — {@code robots.size()}
 * @param toContact  of those, not yet touched or still marked to contact
 * @param contacted  contacted, not yet resolved
 * @param resolved   resolved
 */
public record ZeroDataRobotsResponse(
        String month,
        String monthLabel,
        int inScope,
        int zeroData,
        int toContact,
        int contacted,
        int resolved,
        List<Robot> robots) {

    /**
     * Why there is no data — the three cases the customer success team must tell
     * apart before calling anyone.
     */
    public enum Reason {
        /** No successful sync has ever brought this robot a task. Check the serial and brand. */
        NEVER_SYNCED,
        /** A sync was attempted this month and the last one failed. Ours to fix, not the customer's. */
        SYNC_FAILING,
        /** Synced fine and genuinely logged nothing. The robot was off, away, or done. */
        NO_TASKS
    }

    /** Where the deployment's contract stands relative to today. */
    public enum ContractStatus { NONE, ACTIVE, ENDING_SOON, ENDED }

    /**
     * @param lastDataDate      business-zone date of the last task this robot ever
     *                          logged, or null if it never has
     * @param daysSinceLastData days from that date to today; null when never
     * @param lastSyncError     the last sync failure's message, null after a success
     * @param daysToContractEnd negative once ended; null when no end date
     * @param followupStatus    null until somebody touches the entry
     */
    public record Robot(
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
            ContractStatus contractStatus,
            Long daysToContractEnd,
            LocalDate lastDataDate,
            Long daysSinceLastData,
            Instant lastSyncAttemptAt,
            Instant lastSyncSuccessAt,
            String lastSyncError,
            Reason reason,
            ZeroDataFollowup.Status followupStatus,
            ZeroDataFollowup.Outcome followupOutcome,
            String followupNote,
            String followupUpdatedBy,
            Instant followupUpdatedAt,
            boolean excludedFromReport) {
    }
}
