package com.raaspal.robotrecommendation.partner.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.partner.security.PartnerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The partner-facing, read-only API ({@code /api/partner/v1/**}). Authenticated by
 * {@code X-API-Key} on the dedicated partner security chain
 * ({@code PartnerSecurityConfig}), never by JWT.
 *
 * <p>Every endpoint is scoped to the calling partner via the injected
 * {@link PartnerPrincipal} — the partner identity always comes from the
 * authenticated key, <strong>never</strong> from a request parameter, so a
 * partner can only ever see its own data.
 */
@RestController
@RequestMapping("/api/partner/v1")
@RequiredArgsConstructor
public class PartnerApiController {

    /**
     * Identity probe: returns the partner the presented API key belongs to.
     * Useful for a partner to confirm their key works and see who they are.
     */
    @GetMapping("/me")
    public ApiResponse<PartnerPrincipal> me(@AuthenticationPrincipal PartnerPrincipal principal) {
        return ApiResponse.success(principal);
    }
}