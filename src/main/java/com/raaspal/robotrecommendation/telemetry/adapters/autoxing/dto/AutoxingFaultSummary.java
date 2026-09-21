package com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto;

import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * One robot's recorded faults over a period, from {@code robot_fault_event}.
 *
 * <p>{@link #recordingSince} matters as much as the counts: faults are only known from the
 * day the poller started, so a period that began earlier is only partly covered and the
 * report has to say so rather than imply a clean record.
 */
@Builder
public record AutoxingFaultSummary(
        /** First poll ever recorded, or null if the poller has never run. */
        Instant recordingSince,
        /** True when recording started after the period began - the counts are partial. */
        boolean partialPeriod,
        int errorOccurrences,
        List<FaultItem> errors,
        int emergencyStops,
        long emergencyStopSeconds,
        long offlineSeconds,
        List<FaultEvent> events
) {

    /** One error code over the period: how often it opened and how long it was active in total. */
    public record FaultItem(int code, String message, Integer level, int occurrences, long activeSeconds,
                            boolean activeNow) {
    }

    public record FaultEvent(String kind, Integer code, String message, Instant firstSeenAt, Instant clearedAt) {
    }
}
