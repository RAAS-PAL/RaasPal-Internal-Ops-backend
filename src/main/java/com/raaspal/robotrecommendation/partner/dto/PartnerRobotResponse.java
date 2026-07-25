package com.raaspal.robotrecommendation.partner.dto;

import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;

/**
 * A robot as seen by the partner that services it. Exposes only what a partner
 * needs — the serial number (its handle for task-report queries), the robot's
 * make, the site, and the end-customer it serves. Internal ids
 * (robot_unit_id, deployment_id, customer_profile_id) are deliberately withheld.
 */
public record PartnerRobotResponse(
        String serialNumber,
        String brand,
        String model,
        String name,
        String site,
        String customerName) {

    public static PartnerRobotResponse of(Deployment deployment) {
        RobotUnit robot = deployment.getRobotUnit();
        return new PartnerRobotResponse(
                robot.getSerialNumber(),
                robot.getBrand(),
                robot.getModel(),
                robot.getName(),
                deployment.getSite(),
                deployment.getCustomerProfile() != null
                        ? deployment.getCustomerProfile().getCompanyName()
                        : null);
    }
}