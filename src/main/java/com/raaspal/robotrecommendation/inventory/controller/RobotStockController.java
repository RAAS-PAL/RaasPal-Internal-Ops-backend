package com.raaspal.robotrecommendation.inventory.controller;

import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.inventory.dto.RobotStockEntryRequest;
import com.raaspal.robotrecommendation.inventory.dto.RobotStockEntryResponse;
import com.raaspal.robotrecommendation.inventory.service.RobotStockService;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The warehouse's robot list, kept by the inventory team.
 *
 * <p>Backed by {@code robot_inventory_temp} and connected to nothing else — not the
 * fleet in {@code robot_units}, not the sales catalogue in {@code robots}. What is
 * recorded here is what someone counted on the floor.
 *
 * <p>Reads are open to any signed-in staff member; writes are warehouse staff and
 * admins. Same split as the parts inventory, enforced per method because reads and
 * writes share a path and differ only by verb.
 */
@RestController
@RequestMapping("/api/v1/inventory/robot-stock")
@RequiredArgsConstructor
public class RobotStockController {

    private static final String CAN_WRITE = "hasAnyRole('ADMIN','INVENTORY_STAFF')";

    private final RobotStockService robotStockService;

    /** Everything held. Omit {@code status} for stock and demo together. */
    @GetMapping
    public ApiResponse<List<RobotStockEntryResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) RobotUnitStatus status) {
        return ApiResponse.success(robotStockService.list(q, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<RobotStockEntryResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(robotStockService.getById(id));
    }

    @PostMapping
    @PreAuthorize(CAN_WRITE)
    public ApiResponse<RobotStockEntryResponse> create(
            @Valid @RequestBody RobotStockEntryRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Robot added",
                robotStockService.create(request, principal == null ? null : principal.getId()));
    }

    /**
     * A robot's photo, as an image response rather than JSON.
     * <p>
     * Deliberately its own endpoint: lists carry {@code hasImage} and the browser
     * comes here for the bytes, so a list stays small, images load in parallel and
     * each one is cached on its own URL. Readable by any signed-in staff member,
     * matching the read rule for the rest of the inventory surface.
     */
    @GetMapping("/{id}/image")
    public ResponseEntity<Resource> image(@PathVariable UUID id) {
        return robotStockService.getImage(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(CAN_WRITE)
    public ApiResponse<RobotStockEntryResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody RobotStockEntryRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success("Robot updated",
                robotStockService.update(id, request, principal == null ? null : principal.getId()));
    }

    /** Remove a row entered by mistake. Nothing references these, so it is a real delete. */
    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_WRITE)
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        robotStockService.delete(id);
        return ApiResponse.success("Robot removed");
    }
}
