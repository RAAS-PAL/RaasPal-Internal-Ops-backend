package com.raaspal.robotrecommendation.casereport.brand;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The brands the Service Analysis page can show, in configured order:
 * {@code GET /api/v1/ticket-brands}.
 *
 * <p>Its own path rather than {@code /api/v1/tickets/brands}, where it would sit on top
 * of {@link BrandTicketController}'s {@code /api/v1/tickets/{brand}}. Read from
 * {@code app.tickets.brands}, so a brand added there appears on the page without a
 * frontend release.
 */
@RestController
@RequestMapping("/api/v1/ticket-brands")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','RAASPAL_TEAM')")
public class TicketBrandController {

    private final BrandTicketProperties properties;

    public record TicketBrand(String key, String label) {
    }

    @GetMapping
    public ApiResponse<List<TicketBrand>> brands() {
        return ApiResponse.success(properties.getBrands().stream()
                .map(b -> new TicketBrand(b.getKey(), b.getLabel()))
                .toList());
    }
}
