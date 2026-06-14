package com.raaspal.robotrecommendation.telemetry.adapters.gausium.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Remaining consumable life, as a percentage, reported for a task.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GausiumConsumablesResidual(
        BigDecimal brush,
        BigDecimal filter,
        BigDecimal suctionBlade
) {
}
