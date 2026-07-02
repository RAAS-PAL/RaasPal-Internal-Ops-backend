package com.raaspal.robotrecommendation.robotunit.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.robotunit.dto.RegisterRobotRequest;
import com.raaspal.robotrecommendation.robotunit.dto.RobotUnitResponse;
import com.raaspal.robotrecommendation.robotunit.dto.UpdateCadenceRequest;
import com.raaspal.robotrecommendation.robotunit.dto.UpdateRobotRequest;
import com.raaspal.robotrecommendation.robotunit.service.RobotUnitService;
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
 * Manage robot units, their deployment to customers, and report cadence.
 * Every endpoint is authenticated via SecurityConfig.
 *
 * <p>Bidirectional search:
 * <ul>
 *   <li>{@code GET /api/v1/robot-units?customerId=...} — robots owned by a customer</li>
 *   <li>{@code GET /api/v1/robot-units?serialNumber=...} — the robot + its owning customer</li>
 *   <li>{@code GET /api/v1/robot-units} — all registered robots</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/robot-units")
@RequiredArgsConstructor
public class RobotUnitController {

    private final RobotUnitService robotUnitService;

    /** Register a robot by serial number and deploy it to a customer. */
    @PostMapping
    public ApiResponse<RobotUnitResponse> register(@Valid @RequestBody RegisterRobotRequest request) {
        return ApiResponse.success("Robot registered", robotUnitService.register(request));
    }

    /**
     * List robots, optionally filtered. When {@code serialNumber} is supplied the
     * result holds the single matching robot (with its customer); when
     * {@code customerId} is supplied it holds that customer's robots; otherwise all.
     */
    @GetMapping
    public ApiResponse<List<RobotUnitResponse>> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String serialNumber) {
        if (serialNumber != null && !serialNumber.isBlank()) {
            return ApiResponse.success(List.of(robotUnitService.getBySerialNumber(serialNumber)));
        }
        if (customerId != null) {
            return ApiResponse.success(robotUnitService.listByCustomer(customerId));
        }
        return ApiResponse.success(robotUnitService.listAll());
    }

    /** Edit a robot's details and deployment (serial number is immutable). */
    @PutMapping("/{robotUnitId}")
    public ApiResponse<RobotUnitResponse> update(
            @PathVariable UUID robotUnitId,
            @Valid @RequestBody UpdateRobotRequest request) {
        return ApiResponse.success("Robot updated", robotUnitService.update(robotUnitId, request));
    }

    /** Change a deployment's report cadence (Monthly / Weekly / Off). */
    @PatchMapping("/deployments/{deploymentId}/cadence")
    public ApiResponse<RobotUnitResponse> updateCadence(
            @PathVariable UUID deploymentId,
            @Valid @RequestBody UpdateCadenceRequest request) {
        return ApiResponse.success("Cadence updated",
                robotUnitService.updateCadence(deploymentId, request.reportCadence()));
    }

    /** Deactivate a deployment so the robot stops being reported on. */
    @DeleteMapping("/deployments/{deploymentId}")
    public ApiResponse<Void> deactivate(@PathVariable UUID deploymentId) {
        robotUnitService.deactivate(deploymentId);
        return ApiResponse.success("Deployment deactivated");
    }
}
