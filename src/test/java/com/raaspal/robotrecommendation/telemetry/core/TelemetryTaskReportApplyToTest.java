package com.raaspal.robotrecommendation.telemetry.core;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refresh path repairs rows stored before a mapping fix, so it must overwrite
 * the brand-sourced fields while leaving the row's identity and links alone.
 */
class TelemetryTaskReportApplyToTest {

    @Test
    void applyToOverwritesStaleBrandValues() {
        // A row as stored before the mapName fix: blank map, older figures.
        RobotTaskReport stored = RobotTaskReport.builder()
                .externalTaskId("task-1")
                .brand("GAUSIUM")
                .mapName("")
                .cleaningPlan("Old plan")
                .startBatteryPct(50)
                .build();

        TelemetryTaskReport fresh = TelemetryTaskReport.builder()
                .externalTaskId("task-1")
                .brand("GAUSIUM")
                .mapName("SPD_3")
                .cleaningPlan("North_Jewel_South_2")
                .startBatteryPct(100)
                .startTime(Instant.parse("2026-07-19T16:29:08Z"))
                .build();

        fresh.applyTo(stored);

        assertThat(stored.getMapName()).isEqualTo("SPD_3");
        assertThat(stored.getCleaningPlan()).isEqualTo("North_Jewel_South_2");
        assertThat(stored.getStartBatteryPct()).isEqualTo(100);
    }

    /** Identity and linkage belong to the caller — a refresh must not disturb them. */
    @Test
    void applyToLeavesIdentityAndLinksUntouched() {
        UUID id = UUID.randomUUID();
        RobotUnit robot = RobotUnit.builder().serialNumber("SN-1").brand("GAUSIUM").build();
        CustomerProfile customer = CustomerProfile.builder().companyName("Acme").build();
        Instant originalSync = Instant.parse("2026-06-01T00:00:00Z");

        RobotTaskReport stored = RobotTaskReport.builder()
                .id(id)
                .externalTaskId("task-1")
                .robotUnit(robot)
                .customerProfile(customer)
                .reportMonth("2026-06")
                .syncedAt(originalSync)
                .build();

        TelemetryTaskReport.builder()
                .externalTaskId("task-1")
                .mapName("SPD_3")
                .build()
                .applyTo(stored);

        assertThat(stored.getId()).isEqualTo(id);
        assertThat(stored.getRobotUnit()).isSameAs(robot);
        assertThat(stored.getCustomerProfile()).isSameAs(customer);
        assertThat(stored.getReportMonth()).isEqualTo("2026-06");
        assertThat(stored.getSyncedAt()).isEqualTo(originalSync);
    }

    /** toEntity() must stay in step with applyTo() — they share one field list. */
    @Test
    void toEntityProducesTheSameMappedValuesAsApplyTo() {
        TelemetryTaskReport report = TelemetryTaskReport.builder()
                .externalTaskId("task-9")
                .brand("GAUSIUM")
                .mapName("SPD_3")
                .cleaningPlan("North_Jewel")
                .operator("user")
                .cleaningMode("尘推")
                .startBatteryPct(100)
                .endBatteryPct(78)
                .build();

        RobotTaskReport fromNew = report.toEntity();
        RobotTaskReport ontoExisting = report.applyTo(new RobotTaskReport());

        assertThat(fromNew.getMapName()).isEqualTo(ontoExisting.getMapName());
        assertThat(fromNew.getCleaningPlan()).isEqualTo(ontoExisting.getCleaningPlan());
        assertThat(fromNew.getOperator()).isEqualTo(ontoExisting.getOperator());
        assertThat(fromNew.getCleaningMode()).isEqualTo(ontoExisting.getCleaningMode());
        assertThat(fromNew.getEndBatteryPct()).isEqualTo(ontoExisting.getEndBatteryPct());
    }
}
