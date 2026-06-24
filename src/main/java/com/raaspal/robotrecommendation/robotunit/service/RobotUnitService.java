package com.raaspal.robotrecommendation.robotunit.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.robotunit.dto.RegisterRobotRequest;
import com.raaspal.robotrecommendation.robotunit.dto.RobotUnitResponse;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.ReportCadence;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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

        RobotUnit robot = robotUnitRepository.save(RobotUnit.builder()
                .serialNumber(serialNumber)
                .brand(request.brand().trim())
                .model(request.model())
                .name(request.name())
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

    /** All registered robots with their active deployment (if any). */
    @Transactional(readOnly = true)
    public List<RobotUnitResponse> listAll() {
        return robotUnitRepository.findAll().stream()
                .map(robot -> RobotUnitResponse.of(robot, activeDeploymentFor(robot.getId())))
                .toList();
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
