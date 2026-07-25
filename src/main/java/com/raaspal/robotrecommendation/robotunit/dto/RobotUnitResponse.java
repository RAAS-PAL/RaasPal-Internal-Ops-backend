package com.raaspal.robotrecommendation.robotunit.dto;

import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.ReportCadence;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;

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
            UUID partnerId) {
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
                    deployment.getPartnerId());
        }
        return new RobotUnitResponse(
                robot.getId(),
                robot.getSerialNumber(),
                robot.getBrand(),
                robot.getModel(),
                robot.getName(),
                info);
    }
}
