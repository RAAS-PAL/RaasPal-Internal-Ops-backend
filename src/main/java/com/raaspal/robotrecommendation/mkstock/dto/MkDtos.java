package com.raaspal.robotrecommendation.mkstock.dto;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Requests and views for MK spare parts. */
public final class MkDtos {

    private MkDtos() {
    }

    /* ─── Parts ──────────────────────────────────────────────────────────── */

    /** {@code openingQuantity} is only read on create: it becomes an IN movement "Opening stock". */
    public record PartRequest(
            @NotBlank @Size(max = 64) String partNo,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 100) String robotModel,
            @Size(max = 20) String unit,
            @Min(0) @Max(1_000_000) Integer minLevel,
            @Size(max = 200) String location,
            @Size(max = 1000) String note,
            @Min(0) @Max(1_000_000) Integer openingQuantity,
            Boolean active) {
    }

    /** {@code status}: OK | LOW | OUT. */
    public record PartView(UUID id, String partNo, String name, String robotModel, String unit, int minLevel,
                           String location, String note, int quantityOnHand, String status, boolean active,
                           LocalDate lastMovementOn, Instant updatedAt, boolean hasImage) {
    }

    /** {@code image}: a base64 {@code data:image/...} URI (RIMS resizes photos before sending). */
    public record ImageRequest(@NotBlank String image) {
    }

    /* ─── Movements ──────────────────────────────────────────────────────── */

    /**
     * {@code quantity} is always positive for IN and OUT (the type sets the direction);
     * for ADJUST it is signed (-3 removes three). {@code reason} is required for OUT and ADJUST.
     */
    public record MovementRequest(
            @NotBlank @Pattern(regexp = "IN|OUT|ADJUST") String type,
            @NotNull Integer quantity,
            @Size(max = 500) String reason,
            @Size(max = 100) String reference,
            LocalDate movedOn) {
    }

    /** {@code createdBy} is null in MK's read-only view. */
    public record MovementView(UUID id, UUID partId, String partNo, String partName, String type,
                               int quantityChange, int balanceAfter, String reason, String reference,
                               LocalDate movedOn, String createdBy, Instant createdAt) {
    }

    /* ─── Dashboard ──────────────────────────────────────────────────────── */

    public record SeriesPoint(LocalDate start, int in, int out) {
    }

    public record PartUsage(UUID partId, String partNo, String name, String unit, int units) {
    }

    public record ReasonCount(String reason, int movements, int units) {
    }

    /** {@code granularity}: DAY | WEEK | MONTH, picked from the length of the period. */
    public record Dashboard(LocalDate from, LocalDate to, int parts, int unitsOnHand, int lowStock, int outOfStock,
                            int unitsIn, int unitsOut, int adjustments, int movements, String granularity,
                            List<SeriesPoint> series, List<PartUsage> topOut, List<ReasonCount> outReasons,
                            List<PartView> attention, List<MovementView> recent) {
    }

    /* ─── MK access ──────────────────────────────────────────────────────── */

    public record PinRequest(@NotBlank @Pattern(regexp = "\\d{6,12}", message = "The PIN must be 6 to 12 digits") String pin) {
    }

    public record AccessStatus(boolean pinSet, Instant pinCreatedAt, String pinCreatedBy, Instant lastUsedAt,
                               long activeSessions) {
    }

    /** A freshly generated PIN - returned this once, never stored in plain text. */
    public record PinReset(String pin, AccessStatus status) {
    }

    public record ViewLoginRequest(@NotBlank @Size(max = 20) String pin) {
    }

    public record ViewSession(String token, Instant expiresAt) {
    }
}
