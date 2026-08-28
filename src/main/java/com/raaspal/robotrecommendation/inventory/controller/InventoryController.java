package com.raaspal.robotrecommendation.inventory.controller;

import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.common.response.PagedResponse;
import com.raaspal.robotrecommendation.inventory.dto.*;
import com.raaspal.robotrecommendation.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The RIMS inventory surface.
 *
 * <p><strong>Reads are open to the whole team; writes are not.</strong> Anyone signed
 * in can see what is in stock — that is the point of a shared record — but only
 * warehouse staff and admins may move a count. The split is enforced here with
 * {@code @PreAuthorize} rather than by URL pattern, because both live under the same
 * path and only the HTTP verb distinguishes them.
 *
 * <p>RIMS's own {@code lib/rbac.ts} decides what to <em>render</em>. That is
 * navigation, not security: hiding a button does not stop a request.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private static final String CAN_WRITE = "hasAnyRole('ADMIN','INVENTORY_STAFF')";

    private final InventoryService inventoryService;

    /* ─── Reads — any signed-in staff member ──────────────────────────────── */

    @GetMapping("/items")
    public ApiResponse<PagedResponse<InventoryItemResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            /** Only parts linked to this warehouse robot — the robot detail page's query. */
            @RequestParam(required = false) UUID robotStockId,
            @RequestParam(defaultValue = "false") boolean lowStock,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            Pageable pageable) {
        return ApiResponse.success(PagedResponse.of(
                inventoryService.search(q, category, robotStockId, lowStock, includeInactive, pageable)));
    }

    @GetMapping("/items/{id}")
    public ApiResponse<InventoryItemResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(inventoryService.getById(id));
    }

    /** Stock history for one item, newest first. */
    @GetMapping("/items/{id}/movements")
    public ApiResponse<PagedResponse<StockMovementResponse>> history(@PathVariable UUID id, Pageable pageable) {
        return ApiResponse.success(PagedResponse.of(inventoryService.getHistory(id, pageable)));
    }

    /** Recent movements across every item — the activity feed. */
    @GetMapping("/movements")
    public ApiResponse<PagedResponse<StockMovementResponse>> movements(Pageable pageable) {
        return ApiResponse.success(PagedResponse.of(inventoryService.getRecentMovements(pageable)));
    }

    /** Dashboard header, including the low-stock alert. */
    @GetMapping("/summary")
    public ApiResponse<InventorySummaryResponse> summary() {
        return ApiResponse.success(inventoryService.getSummary());
    }

    /** Categories actually in use, for the filter control. */
    @GetMapping("/categories")
    public ApiResponse<List<String>> categories() {
        return ApiResponse.success(inventoryService.getCategories());
    }


    /* ─── Writes — warehouse staff and admins ─────────────────────────────── */

    @PostMapping("/items")
    @PreAuthorize(CAN_WRITE)
    public ApiResponse<InventoryItemResponse> create(@Valid @RequestBody InventoryItemRequest request) {
        return ApiResponse.success(inventoryService.create(request));
    }

    @PutMapping("/items/{id}")
    @PreAuthorize(CAN_WRITE)
    public ApiResponse<InventoryItemResponse> update(@PathVariable UUID id,
                                                     @Valid @RequestBody InventoryItemRequest request) {
        return ApiResponse.success(inventoryService.update(id, request));
    }

    /**
     * Change a stock level. The only route by which a count moves.
     *
     * <p>The actor comes from the authenticated principal, never the request body —
     * otherwise the audit trail records whoever the client claims to be.
     */
    @PostMapping("/items/{id}/movements")
    @PreAuthorize(CAN_WRITE)
    public ApiResponse<StockMovementResponse> adjust(@PathVariable UUID id,
                                                     @Valid @RequestBody StockMovementRequest request,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(
                inventoryService.recordMovement(id, request, principal == null ? null : principal.getId()));
    }
}
