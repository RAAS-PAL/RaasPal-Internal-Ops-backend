package com.raaspal.robotrecommendation.report.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.report.dto.CustomerReportBundleResponse;
import com.raaspal.robotrecommendation.report.service.CustomerReportLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public record TokenResponse(String token) {}

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
