package com.raaspal.robotrecommendation.mkstock.controller;

import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.mkstock.dto.MkDtos.*;
import com.raaspal.robotrecommendation.mkstock.service.MkAccessService;
import com.raaspal.robotrecommendation.mkstock.service.MkStockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * MK spare parts for RAAS PAL staff: {@code /api/v1/mk-stock}. Any internal account may read
 * and move stock; only an admin may set or turn off MK's PIN.
 */
@RestController
@RequestMapping("/api/v1/mk-stock")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','INVENTORY_STAFF','RAASPAL_TEAM')")
public class MkStockController {

    private final MkStockService stock;
    private final MkAccessService access;

    @GetMapping("/parts")
    public ApiResponse<List<PartView>> parts(@RequestParam(defaultValue = "false") boolean includeRetired) {
        return ApiResponse.success(stock.listParts(includeRetired));
    }

    @GetMapping("/parts/{id}")
    public ApiResponse<PartView> part(@PathVariable UUID id) {
        return ApiResponse.success(stock.getPart(id));
    }

    @PostMapping("/parts")
    public ApiResponse<PartView> createPart(@Valid @RequestBody PartRequest req, @AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.success("Part added", stock.createPart(req, actor(me)));
    }

    @PutMapping("/parts/{id}")
    public ApiResponse<PartView> updatePart(@PathVariable UUID id, @Valid @RequestBody PartRequest req) {
        return ApiResponse.success("Part updated", stock.updatePart(id, req));
    }

    @PostMapping("/parts/{id}/movements")
    public ApiResponse<MovementView> move(@PathVariable UUID id, @Valid @RequestBody MovementRequest req,
                                          @AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.success("Stock updated", stock.recordMovement(id, req, actor(me)));
    }

    @GetMapping("/parts/{id}/movements")
    public ApiResponse<List<MovementView>> history(@PathVariable UUID id) {
        return ApiResponse.success(stock.partHistory(id, false));
    }

    @GetMapping("/movements")
    public ApiResponse<List<MovementView>> movements() {
        return ApiResponse.success(stock.recentMovements(false));
    }

    @GetMapping("/dashboard")
    public ApiResponse<Dashboard> dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(stock.dashboard(from, to, false));
    }

    /* ─── MK's PIN ───────────────────────────────────────────────────────── */

    @GetMapping("/access")
    public ApiResponse<AccessStatus> accessStatus() {
        return ApiResponse.success(access.status());
    }

    @PutMapping("/access/pin")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AccessStatus> setPin(@Valid @RequestBody PinRequest req, @AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.success("PIN set - share it with MK", access.setPin(req.pin(), actor(me)));
    }

    /** Makes a new random PIN and returns it once, to hand to MK. */
    @PostMapping("/access/pin/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PinReset> resetPin(@AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.success("New PIN made - share it with MK", access.resetPin(actor(me)));
    }

    @DeleteMapping("/access/pin")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AccessStatus> disable(@AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.success("MK access turned off", access.disable(actor(me)));
    }

    private static String actor(UserPrincipal me) {
        return me == null ? "system" : me.getUsername();
    }
}
