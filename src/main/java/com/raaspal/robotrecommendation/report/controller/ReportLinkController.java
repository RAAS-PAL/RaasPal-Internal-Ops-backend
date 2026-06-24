package com.raaspal.robotrecommendation.report.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse;
import com.raaspal.robotrecommendation.report.service.ReportLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shareable report links. {@code POST /links} (authenticated) mints the token a
 * staff member shares; {@code GET /public/{token}} is whitelisted in
 * SecurityConfig so the customer can open it without an account — the same URL
 * the monthly email links to.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportLinkController {

    private final ReportLinkService reportLinkService;

    /** A minted shareable token (open at /report/{token}). */
    public record TokenResponse(String token) {
    }

    @PostMapping("/links")
    public ApiResponse<TokenResponse> createLink(
            @RequestParam String serialNumber,
            @RequestParam String month) {
        return ApiResponse.success(new TokenResponse(reportLinkService.createOrGetToken(serialNumber, month)));
    }

    @GetMapping("/public/{token}")
    public ApiResponse<ReportPreviewResponse> publicReport(@PathVariable String token) {
        return ApiResponse.success(reportLinkService.resolve(token));
    }
}
