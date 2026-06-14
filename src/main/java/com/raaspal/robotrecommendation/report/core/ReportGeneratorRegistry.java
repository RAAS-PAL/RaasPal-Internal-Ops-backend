package com.raaspal.robotrecommendation.report.core;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Looks up the {@link ReportGenerator} responsible for a given robot brand.
 * Adding support for a new brand only requires registering a new
 * {@code ReportGenerator} bean - no changes to this registry are needed.
 */
@Component
@RequiredArgsConstructor
public class ReportGeneratorRegistry {

    private final List<ReportGenerator> generators;

    public ReportGenerator getGenerator(String brand) {
        return generators.stream()
                .filter(generator -> generator.supports(brand))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("No report generator registered for brand: " + brand));
    }
}
