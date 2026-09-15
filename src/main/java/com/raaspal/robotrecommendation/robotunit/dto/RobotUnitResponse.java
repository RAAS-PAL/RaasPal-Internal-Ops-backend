package com.raaspal.robotrecommendation.robotunit.dto;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.ReportCadence;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.util.UUID;

/**
 * A robot unit together with its active deployment (the customer that owns it,
 * the site, and the report cadence). Carrying both sides of the link in one
 * payload lets the UI show the robot when searching by customer, and the
 * customer when searching by serial number.
 */
public record RobotUnitResponse(
        UUID id,
        String serialNumber,
        String brand,
        String model,
        String name,
        /* ─── Stock (V28) — internal staff only. These must never reach the partner
           API: status and location tell an external service partner which robots we
           hold and where, and which customers bought rather than rented. That surface
           builds PartnerRobotResponse separately and deliberately omits them. ─── */
        RobotUnitStatus status,
        String version,
        RobotType robotType,
        UUID robotId,
        String location,
        DeploymentInfo deployment) {

    /** The active deployment side of the link; {@code null} if the robot is not deployed. */
    public record DeploymentInfo(
            UUID deploymentId,
            UUID customerProfileId,
            String customerName,
            String site,
            ReportCadence reportCadence,
            boolean active,
            /** Distributor/service partner servicing this deployment; {@code null} = RAASPAL-direct. */
            UUID partnerId,

            /** When the contract started; monthly reports clip to it. Null = whole month. */
            LocalDate contractStartDate,

            /** When it ends, inclusive; the last report clips to it. Null = no end known. */
            LocalDate contractEndDate,

            /** NONE (no end date) / ACTIVE / ENDING_SOON (within 30 days) / ENDED, as of today. */
            ContractExpiryResponse.Status contractStatus,

            /** Days from today to the end date; negative once ended; null when no end date. */
            Long daysToContractEnd) {
    }

    /** Build a response from a robot and (optionally) its active deployment. */
    public static RobotUnitResponse of(RobotUnit robot, Deployment deployment) {
        DeploymentInfo info = null;
        if (deployment != null) {
            info = new DeploymentInfo(
                    deployment.getId(),
                    deployment.getCustomerProfile().getId(),
                    deployment.getCustomerProfile().getCompanyName(),
                    deployment.getSite(),
                    deployment.getReportCadence(),
                    Boolean.TRUE.equals(deployment.getIsActive()),
                    deployment.getPartnerId(),
                    deployment.getContractStartDate(),
                    deployment.getContractEndDate(),
                    contractStatus(deployment.getContractEndDate()),
                    deployment.getContractEndDate() == null
                            ? null
                            : ChronoUnit.DAYS.between(LocalDate.now(BUSINESS_ZONE), deployment.getContractEndDate()));
        }
        return new RobotUnitResponse(
                robot.getId(),
                robot.getSerialNumber(),
                robot.getBrand(),
                robot.getModel(),
                robot.getName(),
                robot.getStatus(),
                robot.getVersion(),
                robot.getRobotType(),
                robot.getRobotId(),
                robot.getLocation(),
                info);
    }

    /** A unit sitting in the warehouse — no deployment, by definition. */
    public static RobotUnitResponse fromStock(RobotUnit robot) {
        return of(robot, null);
    }

    /** The zone the "today" in contract status is taken in — the customer's day, not UTC. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    /** Same rule as {@code ContractExpiryService}: a 30-day window. */
    private static ContractExpiryResponse.Status contractStatus(LocalDate end) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (end == null) return ContractExpiryResponse.Status.NONE;
        if (end.isBefore(today)) return ContractExpiryResponse.Status.ENDED;
        if (!end.isAfter(today.plusDays(30))) return ContractExpiryResponse.Status.ENDING_SOON;
        return ContractExpiryResponse.Status.ACTIVE;
    }
}
