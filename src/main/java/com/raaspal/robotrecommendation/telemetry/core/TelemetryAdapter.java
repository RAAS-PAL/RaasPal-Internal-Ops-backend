package com.raaspal.robotrecommendation.telemetry.core;

import java.time.LocalDate;
import java.util.List;

/**
 * Brand-specific telemetry adapter. Each robot brand (Gausium, etc.) provides
 * one implementation that knows how to call that brand's API and translate
 * its responses into {@link TelemetryTaskReport}s.
 */
public interface TelemetryAdapter {

    /** The brand this adapter handles, e.g. "GAUSIUM". */
    String getBrand();

    /** Whether this adapter handles the given brand (case-insensitive). */
    boolean supports(String brand);

    /**
     * Whether this adapter has the credentials it needs to call its brand API.
     * The scheduled sync checks this first so an unconfigured brand is skipped
     * once with a clear message, instead of throwing for every robot it owns.
     * Adapters that need no credentials inherit {@code true}.
     */
    default boolean isConfigured() {
        return true;
    }

    /**
     * Fetches all task reports for the given robot whose start time falls
     * within {@code [from, to]} (inclusive).
     */
    List<TelemetryTaskReport> fetchTaskReports(String robotSerialNumber, LocalDate from, LocalDate to);
}