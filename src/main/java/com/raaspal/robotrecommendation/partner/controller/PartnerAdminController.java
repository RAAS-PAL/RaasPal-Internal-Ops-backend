package com.raaspal.robotrecommendation.partner.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.common.response.PagedResponse;
import com.raaspal.robotrecommendation.partner.dto.AccessLogResponse;
import com.raaspal.robotrecommendation.partner.dto.ApiKeyResponse;
import com.raaspal.robotrecommendation.partner.dto.AssignPartnerRequest;
import com.raaspal.robotrecommendation.partner.dto.BulkAssignRequest;
import com.raaspal.robotrecommendation.partner.dto.CreateApiKeyRequest;
import com.raaspal.robotrecommendation.partner.dto.CreatePartnerRequest;
import com.raaspal.robotrecommendation.partner.dto.CreatedApiKeyResponse;
import com.raaspal.robotrecommendation.partner.dto.PartnerResponse;
import com.raaspal.robotrecommendation.partner.dto.UpdatePartnerRequest;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService;
import com.raaspal.robotrecommendation.partner.service.PartnerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin console for distributor / service partners: create partners, mint and
 * revoke their API keys, and assign which deployments each partner services.
 *
 * <p>This is the <strong>internal</strong> side — every endpoint sits behind the
 * standard JWT security chain (a logged-in RAASPAL staff member). The
 * <strong>partner-facing</strong> side ({@code /api/partner/v1/**}) is a separate
 * API-key-authenticated chain and lives in {@code PartnerApiController}.
 */
@RestController
@RequestMapping("/api/v1/partners")
@RequiredArgsConstructor
public class PartnerAdminController {

    private final PartnerService partnerService;
    private final PartnerApiKeyService partnerApiKeyService;

    /** Register a new partner. */
    @PostMapping
    public ApiResponse<PartnerResponse> create(@Valid @RequestBody CreatePartnerRequest request) {
        return ApiResponse.success("Partner created", partnerService.create(request.name()));
    }

    /** List all partners. */
    @GetMapping
    public ApiResponse<List<PartnerResponse>> list() {
        return ApiResponse.success(partnerService.list());
    }

    /** Rename a partner and/or enable/disable it (disable = instant kill-switch). */
    @PatchMapping("/{partnerId}")
    public ApiResponse<PartnerResponse> update(
            @PathVariable UUID partnerId,
            @Valid @RequestBody UpdatePartnerRequest request) {
        return ApiResponse.success("Partner updated", partnerService.update(partnerId, request));
    }

    /**
     * Mint a new API key for a partner. The plaintext key is returned
     * <strong>once</strong> in this response and can never be recovered.
     */
    @PostMapping("/{partnerId}/keys")
    public ApiResponse<CreatedApiKeyResponse> createKey(
            @PathVariable UUID partnerId,
            @Valid @RequestBody(required = false) CreateApiKeyRequest request) {
        String label = request != null ? request.label() : null;
        Integer expiresInDays = request != null ? request.expiresInDays() : null;
        CreatedApiKeyResponse created = CreatedApiKeyResponse.of(
                partnerApiKeyService.generate(partnerId, label, expiresInDays));
        return ApiResponse.success("API key created — copy it now, it is shown only once", created);
    }

    /** List a partner's keys (metadata only — no secrets). */
    @GetMapping("/{partnerId}/keys")
    public ApiResponse<List<ApiKeyResponse>> listKeys(@PathVariable UUID partnerId) {
        List<ApiKeyResponse> keys = partnerApiKeyService.listKeys(partnerId).stream()
                .map(ApiKeyResponse::of)
                .toList();
        return ApiResponse.success(keys);
    }

    /**
     * A partner's recorded API requests, newest first — who fetched what, when,
     * with which key, and the outcome. Includes rejected attempts.
     */
    @GetMapping("/{partnerId}/access-log")
    public ApiResponse<PagedResponse<AccessLogResponse>> accessLog(
            @PathVariable UUID partnerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(partnerService.accessLog(partnerId, page, size));
    }

    /** Revoke a key immediately — future requests bearing it are rejected. */
    @DeleteMapping("/keys/{keyId}")
    public ApiResponse<Void> revokeKey(@PathVariable UUID keyId) {
        partnerApiKeyService.revoke(keyId);
        return ApiResponse.success("API key revoked");
    }

    /**
     * Assign a deployment to the partner that services it, or un-assign it
     * (send {@code partnerId: null}). This link scopes the partner's data access.
     */
    @PutMapping("/deployments/{deploymentId}")
    public ApiResponse<Void> assignDeployment(
            @PathVariable UUID deploymentId,
            @RequestBody AssignPartnerRequest request) {
        partnerService.assignDeployment(deploymentId, request.partnerId());
        return ApiResponse.success(
                request.partnerId() != null ? "Deployment assigned to partner" : "Deployment un-assigned");
    }

    /**
     * Bulk-assign many deployments to a partner in one call — the fast path for a
     * partner with dozens of robots. Returns how many were assigned.
     */
    @PutMapping("/{partnerId}/deployments")
    public ApiResponse<Integer> assignDeployments(
            @PathVariable UUID partnerId,
            @Valid @RequestBody BulkAssignRequest request) {
        int count = partnerService.assignDeployments(partnerId, request.deploymentIds());
        return ApiResponse.success("Robots assigned", count);
    }
}
