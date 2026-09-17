package com.raaspal.robotrecommendation.robotunit.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.robotunit.dto.BulkCadenceRequest;
import com.raaspal.robotrecommendation.robotunit.dto.ReceiveStockRequest;
import com.raaspal.robotrecommendation.robotunit.dto.RegisterRobotRequest;
import com.raaspal.robotrecommendation.robotunit.dto.RobotUnitResponse;
import com.raaspal.robotrecommendation.robotunit.dto.UpdateCadenceRequest;
import com.raaspal.robotrecommendation.robotunit.dto.UpdateRobotRequest;
import com.raaspal.robotrecommendation.robotunit.dto.UpdateStockStatusRequest;
import com.raaspal.robotrecommendation.robotunit.dto.UpdateStockUnitRequest;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import com.raaspal.robotrecommendation.robotunit.dto.ContractExpiryResponse;
import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.robotunit.service.ContractDocumentService;
import com.raaspal.robotrecommendation.robotunit.service.ContractExpiryService;
import com.raaspal.robotrecommendation.robotunit.service.RobotUnitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.multipart.MultipartFile;
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
import java.util.Map;
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
    private final ContractExpiryService contractExpiryService;
    private final ContractDocumentService contractDocumentService;

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
            @RequestParam(required = false) String serialNumber,
            @RequestParam(required = false) RobotUnitStatus status) {
        if (serialNumber != null && !serialNumber.isBlank()) {
            return ApiResponse.success(List.of(robotUnitService.getBySerialNumber(serialNumber)));
        }
        if (customerId != null) {
            return ApiResponse.success(robotUnitService.listByCustomer(customerId));
        }
        if (status != null) {
            return ApiResponse.success(robotUnitService.listByStatus(status));
        }
        return ApiResponse.success(robotUnitService.listAll());
    }

    /**
     * Contracts ending within {@code withinDays} (default 30) and contracts already
     * ended, for the console's Contracts view. Only active deployments with an end date.
     */
    @GetMapping("/contracts/expiring")
    public ApiResponse<ContractExpiryResponse> expiringContracts(
            @RequestParam(required = false, defaultValue = "30") int withinDays) {
        return ApiResponse.success(contractExpiryService.list(Math.max(0, Math.min(withinDays, 365))));
    }

    /**
     * Every active deployment as a contract row, for the Contracts page's "All" view.
     * {@code withinDays} decides which rows count as ending soon.
     */
    @GetMapping("/contracts")
    public ApiResponse<ContractExpiryResponse.All> allContracts(
            @RequestParam(required = false, defaultValue = "30") int withinDays) {
        return ApiResponse.success(contractExpiryService.all(Math.max(0, Math.min(withinDays, 365))));
    }

    /**
     * Receive robots into the warehouse — stock, with no customer attached.
     *
     * <p>Restricted to warehouse staff and admins: this creates fleet records, and a
     * mistaken batch has to be unpicked serial by serial.
     */
    @PostMapping("/stock")
    @PreAuthorize("hasAnyRole('ADMIN','INVENTORY_STAFF')")
    public ApiResponse<List<RobotUnitResponse>> receiveStock(@Valid @RequestBody ReceiveStockRequest request) {
        List<RobotUnitResponse> received = robotUnitService.receiveIntoStock(request);
        return ApiResponse.success("Received " + received.size() + " robot(s) into stock", received);
    }

    /** Everything the warehouse holds — available stock plus units out on demo. */
    @GetMapping("/warehouse")
    public ApiResponse<List<RobotUnitResponse>> warehouse() {
        return ApiResponse.success(robotUnitService.listWarehouse());
    }

    /**
     * Move a unit between IN_STOCK and DEMO.
     *
     * <p>RENT and SOLD are rejected: both follow from a customer agreement, and
     * setting them from a warehouse screen would leave the fleet claiming a sale
     * that has no deployment and no record behind it.
     */
    @PatchMapping("/{robotUnitId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','INVENTORY_STAFF')")
    public ApiResponse<RobotUnitResponse> updateStockStatus(
            @PathVariable UUID robotUnitId,
            @Valid @RequestBody UpdateStockStatusRequest request) {
        return ApiResponse.success("Status updated",
                robotUnitService.updateStockStatus(robotUnitId, request));
    }

    /**
     * Edit a robot that is in the warehouse — details, status and photo.
     *
     * <p>Distinct from {@code PUT /{robotUnitId}} below, which edits a robot together
     * with its deployment and therefore requires a customer. A stock unit has none.
     */
    @PutMapping("/{robotUnitId}/stock")
    @PreAuthorize("hasAnyRole('ADMIN','INVENTORY_STAFF')")
    public ApiResponse<RobotUnitResponse> updateStockUnit(
            @PathVariable UUID robotUnitId,
            @Valid @RequestBody UpdateStockUnitRequest request) {
        return ApiResponse.success("Robot updated", robotUnitService.updateStockUnit(robotUnitId, request));
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

    /** Set the report cadence in bulk: all active deployments, or just the selected ids. */
    @PatchMapping("/deployments/cadence")
    public ApiResponse<Integer> updateAllCadence(@Valid @RequestBody BulkCadenceRequest request) {
        int updated = robotUnitService.updateAllCadence(request.reportCadence(), request.deploymentIds());
        return ApiResponse.success("Cadence updated", updated);
    }

    /** Deactivate a deployment so the robot stops being reported on. */
    @DeleteMapping("/deployments/{deploymentId}")
    public ApiResponse<Void> deactivate(@PathVariable UUID deploymentId) {
        robotUnitService.deactivate(deploymentId);
        return ApiResponse.success("Deployment deactivated");
    }
    // ─── Contract documents ──────────────────────────────────────────────────

    /**
     * Attach the signed contract PDF to this robot's deployment - and, if asked, to
     * every other robot of the same customer on the same contract dates. Replaces
     * whatever was attached before. The file is checked to be a PDF by its bytes.
     */
    @PostMapping(value = "/{robotUnitId}/contract-document", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ADMIN','RAASPAL_TEAM')")
    public ApiResponse<ContractDocumentService.Attached> attachContractDocument(
            @PathVariable UUID robotUnitId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "applyToSameContract", defaultValue = "true") boolean applyToSameContract,
            @AuthenticationPrincipal UserPrincipal principal) {
        ContractDocumentService.Attached attached = contractDocumentService.attach(
                robotUnitId, file.getOriginalFilename(), file.getContentType(),
                ContractDocumentService.bytesOf(file), applyToSameContract,
                principal == null ? null : principal.getUsername());
        return ApiResponse.success("Contract attached to " + attached.deploymentsLinked() + " robot(s)", attached);
    }

    /** A five-minute link to the attached PDF. The console opens it in a new tab. */
    @GetMapping("/{robotUnitId}/contract-document/url")
    @PreAuthorize("hasAnyRole('ADMIN','RAASPAL_TEAM')")
    public ApiResponse<Map<String, String>> contractDocumentUrl(@PathVariable UUID robotUnitId) {
        return ApiResponse.success(Map.of("url", contractDocumentService.temporaryUrl(robotUnitId)));
    }

    /** Detach the PDF from this robot. Other robots on the same contract keep it. */
    @DeleteMapping("/{robotUnitId}/contract-document")
    @PreAuthorize("hasAnyRole('ADMIN','RAASPAL_TEAM')")
    public ApiResponse<Void> removeContractDocument(@PathVariable UUID robotUnitId) {
        contractDocumentService.remove(robotUnitId);
        return ApiResponse.success("Contract document removed");
    }
}

