package com.raaspal.robotrecommendation.report.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.report.dto.CustomerBundlePreviewResponse;
import com.raaspal.robotrecommendation.report.dto.CustomerReportBundleResponse;
import com.raaspal.robotrecommendation.report.service.CustomerReportBundleService;
import com.raaspal.robotrecommendation.report.service.CustomerReportExclusionService;
import com.raaspal.robotrecommendation.report.service.CustomerReportLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Customer-level report bundle links. POST (authenticated) mints a stable
 * token per customer+month; GET /public/customer/{token} is permitAll so the
 * customer can open their monthly email link without an account.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class CustomerReportBundleController {

    private final CustomerReportLinkService customerReportLinkService;
    private final CustomerReportBundleService customerReportBundleService;
    private final CustomerReportExclusionService customerReportExclusionService;

    public record TokenResponse(String token) {}

    public record ExclusionsRequest(List<UUID> excludedRobotUnitIds) {}

    public record ExclusionsResponse(Set<UUID> excludedRobotUnitIds) {}

    /**
     * Staff review view: every robot deployed to the customer for the month, each
     * flagged with whether it logged any activity and whether it is currently held
     * back. Authenticated — this is the curation surface, not the customer's page.
     */
    @GetMapping("/customer-bundle/preview")
    public ApiResponse<CustomerBundlePreviewResponse> previewBundle(
            @RequestParam UUID customerProfileId,
            @RequestParam String month) {
        return ApiResponse.success(customerReportBundleService.buildPreview(customerProfileId, month));
    }

    /**
     * Replaces the set of robots held back from this customer's report for this
     * month. Sending an empty list puts every robot back in — the choice is always
     * reversible, and no telemetry is affected either way.
     *
     * <p>Takes effect immediately on the customer's public link as well as the
     * staff preview, so what was approved is what gets read.
     */
    @PutMapping("/customer-bundle/exclusions")
    public ApiResponse<ExclusionsResponse> setExclusions(
            @RequestParam UUID customerProfileId,
            @RequestParam String month,
            @RequestBody ExclusionsRequest request) {
        Set<UUID> saved = customerReportExclusionService.replace(
                customerProfileId, month, request.excludedRobotUnitIds());
        return ApiResponse.success(
                saved.isEmpty()
                        ? "All robots included"
                        : saved.size() + " robot(s) excluded from this month's report",
                new ExclusionsResponse(saved));
    }

    @PostMapping("/links/customer")
    public ApiResponse<TokenResponse> createCustomerLink(
            @RequestParam UUID customerProfileId,
            @RequestParam String month) {
        return ApiResponse.success(
                new TokenResponse(customerReportLinkService.createOrGetToken(customerProfileId, month)));
    }

    @GetMapping("/public/customer/{token}")
    public ApiResponse<CustomerReportBundleResponse> publicBundle(@PathVariable String token) {
        return ApiResponse.success(customerReportLinkService.resolve(token));
    }
}
