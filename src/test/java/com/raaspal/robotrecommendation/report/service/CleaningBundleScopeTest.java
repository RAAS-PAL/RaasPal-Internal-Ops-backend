package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.robotunit.dto.RobotUnitResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Delivery robots stay out of the cleaning bundle until the delivery report joins it. */
class CleaningBundleScopeTest {

    private static RobotUnitResponse robot(RobotType type) {
        return new RobotUnitResponse(UUID.randomUUID(), "SN", "AUTOXING", "Zara", "R", null, null, type,
                null, null, null);
    }

    @Test
    void deliveryRobotsAreLeftOutAndEverythingElseStays() {
        assertThat(CustomerReportBundleService.inCleaningReport(robot(RobotType.DELIVERY))).isFalse();
        assertThat(CustomerReportBundleService.inCleaningReport(robot(RobotType.CLEANING))).isTrue();
        assertThat(CustomerReportBundleService.inCleaningReport(robot(RobotType.MOWING))).isTrue();
    }
}
