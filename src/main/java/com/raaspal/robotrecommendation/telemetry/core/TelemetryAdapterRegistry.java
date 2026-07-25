package com.raaspal.robotrecommendation.telemetry.core;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Looks up the {@link TelemetryAdapter} responsible for a given robot brand.
 * Adding support for a new brand only requires registering a new
 * {@code TelemetryAdapter} bean - no changes to this registry are needed.
 */
@Component
@RequiredArgsConstructor
public class TelemetryAdapterRegistry {

    private final List<TelemetryAdapter> adapters;

    public TelemetryAdapter getAdapter(String brand) {
        return findAdapter(brand)
                .orElseThrow(() -> new BadRequestException("No telemetry adapter registered for brand: " + brand));
    }

    /**
     * The adapter for a brand, or empty if none is registered. Used by the
     * scheduled sync, which must skip brands it cannot handle (e.g. a robot
     * registered under a brand with no integration yet) rather than fail.
     */
    public Optional<TelemetryAdapter> findAdapter(String brand) {
        if (brand == null || brand.isBlank()) {
            return Optional.empty();
        }
        return adapters.stream()
                .filter(adapter -> adapter.supports(brand))
                .findFirst();
    }
}