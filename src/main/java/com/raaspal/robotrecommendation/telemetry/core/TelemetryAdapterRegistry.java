package com.raaspal.robotrecommendation.telemetry.core;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

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
        return adapters.stream()
                .filter(adapter -> adapter.supports(brand))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("No telemetry adapter registered for brand: " + brand));
    }
}