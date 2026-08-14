package com.raaspal.robotrecommendation.robotunit.service;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.robotunit.dto.ReceiveStockRequest;
import com.raaspal.robotrecommendation.robotunit.dto.RegisterRobotRequest;
import com.raaspal.robotrecommendation.robotunit.dto.RobotUnitResponse;
import com.raaspal.robotrecommendation.robotunit.dto.UpdateRobotRequest;
import com.raaspal.robotrecommendation.robotunit.dto.UpdateStockStatusRequest;
import com.raaspal.robotrecommendation.robotunit.dto.UpdateStockUnitRequest;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.ReportCadence;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Manages robot units and their deployment to customers. A robot is identified
 * by its manufacturer serial number and linked to exactly one customer via an
 * active {@link Deployment}, which also carries the report cadence.
 *
 * <p>Search is bidirectional: list by customer to see their robots, or look up
 * a serial number to see the owning customer — both return the same
 * {@link RobotUnitResponse} carrying both sides of the link.
 */
@Service
@RequiredArgsConstructor
public class RobotUnitService {

    private final RobotUnitRepository robotUnitRepository;
    private final DeploymentRepository deploymentRepository;
    private final CustomerProfileRepository customerProfileRepository;

    /**
     * Receives robots into the warehouse — stock, with no customer and no deployment.
     *
     * <p>All-or-nothing: the whole batch is validated before anything is written, so
     * a paste of five serials containing one duplicate saves none of them rather than
     * four. A half-applied delivery is worse than a rejected one, because the operator
     * cannot tell from the screen which rows landed.
     */
    @Transactional
    public List<RobotUnitResponse> receiveIntoStock(ReceiveStockRequest request) {
        // Normalise first: trailing whitespace from a spreadsheet paste is invisible
        // on screen but would create "GS-001 " as a serial distinct from "GS-001".
        List<String> serials = request.serialNumbers().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (serials.isEmpty()) {
            throw new BadRequestException("At least one serial number is required");
        }

        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicatesInRequest = serials.stream()
                .filter(s -> !seen.add(s.toLowerCase()))
                .distinct()
                .toList();
        if (!duplicatesInRequest.isEmpty()) {
            throw new BadRequestException(
                    "Repeated serial numbers in this batch: " + String.join(", ", duplicatesInRequest));
        }

        List<String> alreadyKnown = serials.stream()
                .filter(robotUnitRepository::existsBySerialNumber)
                .toList();
        if (!alreadyKnown.isEmpty()) {
            throw new BadRequestException(
                    "Already registered: " + String.join(", ", alreadyKnown));
        }

        RobotUnitStatus status = request.status() != null ? request.status() : RobotUnitStatus.IN_STOCK;
        if (!status.isWarehouseVisible()) {
            throw new BadRequestException(
                    "Robots can only be received as IN_STOCK or DEMO. " + status
                            + " follows from deploying the robot or recording a sale.");
        }

        RobotType type = request.robotType() != null ? request.robotType() : RobotType.CLEANING;
        String model = request.model() == null || request.model().isBlank() ? null : request.model().trim();
        String location = request.location() == null || request.location().isBlank() ? null : request.location().trim();
        String name = request.name() == null || request.name().isBlank() ? null : request.name().trim();

        // Parse the hardware revision out of the model text the same way V28 did, so
        // units received here are consistent with the 152 already in the fleet.
        String version = model == null ? null : extractVersion(model);

        List<RobotUnit> units = serials.stream()
                .map(serial -> RobotUnit.builder()
                        .serialNumber(serial)
                        .brand(request.brand().trim())
                        .model(model)
                        .name(name)
                        .robotType(type)
                        .robotId(request.robotId())
                        .version(version)
                        .location(location)
                        .status(status)
                        .build())
                .toList();

        return robotUnitRepository.saveAll(units).stream()
                .map(RobotUnitResponse::fromStock)
                .toList();
    }

    /**
     * Edit a unit that is in the warehouse.
     *
     * <p>Refuses a unit that is currently RENT or SOLD: that robot belongs to a
     * deployment, and editing it from a stock screen would let the warehouse rewrite
     * a customer's robot without the account team seeing it.
     */
    @Transactional
    public RobotUnitResponse updateStockUnit(UUID robotUnitId, UpdateStockUnitRequest request) {
        RobotUnit unit = robotUnitRepository.findById(robotUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("RobotUnit", "id", robotUnitId));

        if (!unit.getStatus().isWarehouseVisible()) {
            throw new BadRequestException(
                    "This robot is " + unit.getStatus() + " and belongs to a deployment. "
                            + "Edit it from the robot's deployment instead.");
        }
        if (request.status() != null && !request.status().isWarehouseVisible()) {
            throw new BadRequestException(
                    "Only IN_STOCK and DEMO can be set here. " + request.status()
                            + " follows from deploying the robot or recording a sale.");
        }

        unit.setBrand(request.brand().trim());
        unit.setModel(blankToNull(request.model()));
        unit.setName(blankToNull(request.name()));
        unit.setLocation(blankToNull(request.location()));
        unit.setRobotId(request.robotId());
        if (request.robotType() != null) unit.setRobotType(request.robotType());
        if (request.status() != null)    unit.setStatus(request.status());

        // Re-derive the revision from the (possibly corrected) model text, so a typo
        // fixed here does not leave a stale version behind.
        unit.setVersion(unit.getModel() == null ? null : extractVersion(unit.getModel()));


        return RobotUnitResponse.fromStock(robotUnitRepository.save(unit));
    }


    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** "Phantas V1.1" -> "V1.1". Mirrors the regex used by the V28 backfill. */
    private static String extractVersion(String model) {
        Matcher m = VERSION_PATTERN.matcher(model);
        return m.find() ? m.group(1) : null;
    }

    private static final Pattern VERSION_PATTERN = Pattern.compile("([Vv][0-9.]+)");

    /** Registers a robot by serial number and deploys it to a customer. */
    @Transactional
    public RobotUnitResponse register(RegisterRobotRequest request) {
        String serialNumber = request.serialNumber().trim();
        if (robotUnitRepository.existsBySerialNumber(serialNumber)) {
            throw new BadRequestException(
                    "A robot with serial number '" + serialNumber + "' is already registered");
        }

        CustomerProfile customer = customerProfileRepository.findById(request.customerProfileId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CustomerProfile", "id", request.customerProfileId()));

        // This endpoint registers a robot AND deploys it to a customer in one call,
        // so the unit is at a customer site from the moment it exists — never in
        // stock. RIMS's warehouse list is therefore unaffected by registrations
        // made here. "Receive into stock" is a separate flow that does not exist yet.
        RobotUnit robot = robotUnitRepository.save(RobotUnit.builder()
                .serialNumber(serialNumber)
                .brand(request.brand().trim())
                .model(request.model())
                .name(request.name())
                .robotType(request.robotType() != null ? request.robotType() : RobotType.CLEANING)
                .status(RobotUnitStatus.RENT)
                .build());

        ReportCadence cadence = request.reportCadence() != null
                ? request.reportCadence()
                : ReportCadence.MONTHLY;

        Deployment deployment = deploymentRepository.save(Deployment.builder()
                .robotUnit(robot)
                .customerProfile(customer)
                .site(request.site())
                .isActive(true)
                .reportCadence(cadence)
                .deployedAt(LocalDateTime.now())
                .build());

        return RobotUnitResponse.of(robot, deployment);
    }

    /**
     * Edits a robot's details (brand/model/name) and its deployment (customer,
     * site, cadence). The serial number is immutable. If the robot has an active
     * deployment it is updated in place — including reassigning to another
     * customer; if it has none (deactivated), a fresh active deployment is created.
     */
    @Transactional
    public RobotUnitResponse update(UUID robotUnitId, UpdateRobotRequest request) {
        RobotUnit robot = robotUnitRepository.findById(robotUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("RobotUnit", "id", robotUnitId));

        CustomerProfile customer = customerProfileRepository.findById(request.customerProfileId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CustomerProfile", "id", request.customerProfileId()));

        robot.setBrand(request.brand().trim());
        robot.setModel(request.model());
        robot.setName(request.name());
        robotUnitRepository.save(robot);

        ReportCadence cadence = request.reportCadence() != null
                ? request.reportCadence()
                : ReportCadence.MONTHLY;

        Deployment deployment = activeDeploymentFor(robotUnitId);
        if (deployment == null) {
            deployment = Deployment.builder()
                    .robotUnit(robot)
                    .isActive(true)
                    .deployedAt(LocalDateTime.now())
                    .build();
        }
        deployment.setCustomerProfile(customer);
        deployment.setSite(request.site());
        deployment.setReportCadence(cadence);
        deploymentRepository.save(deployment);

        return RobotUnitResponse.of(robot, deployment);
    }

    /**
     * All registered robots with their active deployment (if any). Uses two
     * queries total — one for the robots, one for all active deployments with
     * robot + customer fetched — instead of an N+1 per-robot deployment lookup,
     * which was slow for accounts with many robots.
     */
    @Transactional(readOnly = true)
    public List<RobotUnitResponse> listAll() {
        Map<UUID, Deployment> activeByRobotId = deploymentRepository.findActiveWithRobotAndCustomer().stream()
                .collect(Collectors.toMap(
                        d -> d.getRobotUnit().getId(),
                        d -> d,
                        (first, ignored) -> first));
        return robotUnitRepository.findAll().stream()
                .map(robot -> RobotUnitResponse.of(robot, activeByRobotId.get(robot.getId())))
                .toList();
    }

    /** Units in one status — {@code IN_STOCK} is the warehouse list RIMS shows. */
    @Transactional(readOnly = true)
    public List<RobotUnitResponse> listByStatus(RobotUnitStatus status) {
        return robotUnitRepository.findByStatusOrderByBrandAscModelAscSerialNumberAsc(status).stream()
                .map(RobotUnitResponse::fromStock)
                .toList();
    }

    /** Everything the warehouse holds: available stock plus units out on demo. */
    @Transactional(readOnly = true)
    public List<RobotUnitResponse> listWarehouse() {
        return robotUnitRepository
                .findByStatusInOrderByBrandAscModelAscSerialNumberAsc(
                        List.of(RobotUnitStatus.IN_STOCK, RobotUnitStatus.DEMO))
                .stream()
                .map(RobotUnitResponse::fromStock)
                .toList();
    }

    /**
     * Move a unit between warehouse states.
     *
     * <p>Only IN_STOCK and DEMO are reachable here. RENT and SOLD describe a
     * commercial arrangement — RENT is produced by deploying the robot, SOLD records
     * a change of ownership — and neither should be settable from a warehouse screen
     * with no customer and no paperwork behind it.
     *
     * <p>A unit currently at a customer is also refused: bringing it back means
     * ending its deployment, which this endpoint does not do. Silently flipping the
     * status would leave an active deployment pointing at a robot the warehouse
     * believes it has on a shelf.
     */
    @Transactional
    public RobotUnitResponse updateStockStatus(UUID robotUnitId, UpdateStockStatusRequest request) {
        RobotUnit unit = robotUnitRepository.findById(robotUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("RobotUnit", "id", robotUnitId));

        if (!request.status().isWarehouseVisible()) {
            throw new BadRequestException(
                    "Only IN_STOCK and DEMO can be set here. " + request.status()
                            + " follows from deploying the robot or recording a sale.");
        }
        if (!unit.getStatus().isWarehouseVisible()) {
            throw new BadRequestException(
                    "This robot is currently " + unit.getStatus()
                            + ". End its deployment before returning it to the warehouse.");
        }

        unit.setStatus(request.status());
        if (request.location() != null) {
            unit.setLocation(request.location().isBlank() ? null : request.location().trim());
        }
        return RobotUnitResponse.fromStock(robotUnitRepository.save(unit));
    }

    /** Robots owned by a customer (search customer → its robots). */
    @Transactional(readOnly = true)
    public List<RobotUnitResponse> listByCustomer(UUID customerProfileId) {
        if (!customerProfileRepository.existsById(customerProfileId)) {
            throw new ResourceNotFoundException("CustomerProfile", "id", customerProfileId);
        }
        return deploymentRepository.findByCustomerProfileIdAndIsActiveTrue(customerProfileId).stream()
                .map(d -> RobotUnitResponse.of(d.getRobotUnit(), d))
                .toList();
    }

    /** A single robot by serial number, including its owning customer (search robot → customer). */
    @Transactional(readOnly = true)
    public RobotUnitResponse getBySerialNumber(String serialNumber) {
        RobotUnit robot = robotUnitRepository.findBySerialNumber(serialNumber.trim())
                .orElseThrow(() -> new ResourceNotFoundException("RobotUnit", "serialNumber", serialNumber));
        return RobotUnitResponse.of(robot, activeDeploymentFor(robot.getId()));
    }

    /**
     * Sets the report cadence in bulk. With no ids, applies to <em>every</em>
     * active deployment; with ids, only to that selection (inactive ones are
     * ignored). Returns how many deployments were updated.
     */
    @Transactional
    public int updateAllCadence(ReportCadence cadence, List<UUID> deploymentIds) {
        List<Deployment> deployments = (deploymentIds == null || deploymentIds.isEmpty())
                ? deploymentRepository.findByIsActiveTrue()
                : deploymentRepository.findAllById(deploymentIds).stream()
                        .filter(d -> Boolean.TRUE.equals(d.getIsActive()))
                        .toList();
        deployments.forEach(d -> d.setReportCadence(cadence));
        deploymentRepository.saveAll(deployments);
        return deployments.size();
    }

    /** Changes a deployment's report cadence (Monthly / Weekly / Off). */
    @Transactional
    public RobotUnitResponse updateCadence(UUID deploymentId, ReportCadence cadence) {
        Deployment deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Deployment", "id", deploymentId));
        deployment.setReportCadence(cadence);
        deploymentRepository.save(deployment);
        return RobotUnitResponse.of(deployment.getRobotUnit(), deployment);
    }

    /** Deactivates a deployment so it stops being reported on. */
    @Transactional
    public void deactivate(UUID deploymentId) {
        Deployment deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Deployment", "id", deploymentId));
        deployment.setIsActive(false);
        deploymentRepository.save(deployment);
    }

    /** The robot's active deployment, or {@code null} if it is not deployed. */
    private Deployment activeDeploymentFor(UUID robotUnitId) {
        return deploymentRepository.findByRobotUnitIdAndIsActiveTrue(robotUnitId).stream()
                .findFirst()
                .orElse(null);
    }
}
