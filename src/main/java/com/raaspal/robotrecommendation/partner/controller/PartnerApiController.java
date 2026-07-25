package com.raaspal.robotrecommendation.partner.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.common.response.PagedResponse;
import com.raaspal.robotrecommendation.partner.dto.PartnerRobotResponse;
import com.raaspal.robotrecommendation.partner.dto.PartnerTaskReportResponse;
import com.raaspal.robotrecommendation.partner.security.PartnerPrincipal;
import com.raaspal.robotrecommendation.partner.service.PartnerDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The partner-facing, read-only API ({@code /api/partner/v1/**}). Authenticated by
 * {@code X-API-Key} on the dedicated partner security chain
 * ({@code PartnerSecurityConfig}), never by JWT.
 *
 * <p>Every endpoint is scoped to the calling partner via the injected
 * {@link PartnerPrincipal} — the partner identity always comes from the
 * authenticated key, <strong>never</strong> from a request parameter, so a
 * partner can only ever see its own data. All reads come from our own database
 * (already-synced telemetry); no live brand-API call happens on the read path.
 */
@RestController
@RequestMapping("/api/partner/v1")
@RequiredArgsConstructor
public class PartnerApiController {

    private final PartnerDataService partnerDataService;

    /**
     * Identity probe: returns the partner the presented API key belongs to.
     * Useful for a partner to confirm their key works and see who they are.
     */
    @GetMapping("/me")
    public ApiResponse<PartnerPrincipal> me(@AuthenticationPrincipal PartnerPrincipal principal) {
        return ApiResponse.success(principal);
    }

    /** The robots this partner services, each with its site and end-customer. */
    @GetMapping("/robots")
    public ApiResponse<List<PartnerRobotResponse>> robots(
            @AuthenticationPrincipal PartnerPrincipal principal) {
        return ApiResponse.success(partnerDataService.listRobots(principal.partnerId()));
    }

    /**
     * Paged task reports for one of the partner's robots (by serial number),
     * most recent first. Optional {@code month} filter ({@code YYYY-MM}).
     * A serial number the partner does not service returns 404.
     */
    @GetMapping("/robots/{serialNumber}/task-reports")
    public ApiResponse<PagedResponse<PartnerTaskReportResponse>> taskReports(
            @AuthenticationPrincipal PartnerPrincipal principal,
            @PathVariable String serialNumber,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(partnerDataService.listTaskReports(
                principal.partnerId(), serialNumber, month, page, size));
    }
}