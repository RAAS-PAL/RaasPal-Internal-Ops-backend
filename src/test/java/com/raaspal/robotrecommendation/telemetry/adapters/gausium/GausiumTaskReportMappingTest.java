package com.raaspal.robotrecommendation.telemetry.adapters.gausium;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.telemetry.adapters.gausium.dto.GausiumTaskReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how a live Gausium task report maps onto our fields. Gausium leaves the
 * top-level {@code areaNameList} empty for whole-map tasks and reports the map in
 * {@code subTasks[].mapName} instead — a nested field we previously discarded
 * (the DTO ignores unknown properties), so every synced row had a blank map name.
 */
class GausiumTaskReportMappingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Trimmed from a real /openapi/v2alpha1/robots/{sn}/taskReports response. */
    private static final String LIVE_REPORT_JSON = """
            {
              "id": "7ce13ec9-a970-462d-8823-09bbce4b716b",
              "displayName": "North_Jewel_South_2",
              "robot": "SPD_M_3",
              "robotSerialNumber": "GS101-0100-66P-T000",
              "operator": "user",
              "completionPercentage": 0.973,
              "durationSeconds": 9763,
              "areaNameList": "",
              "plannedCleaningAreaSquareMeter": 2477.58,
              "actualCleaningAreaSquareMeter": 2411.293,
              "efficiencySquareMeterPerHour": 889.072,
              "consumablesResidualPercentage": { "brush": 99.73, "filter": 99.22, "suctionBlade": 98.52 },
              "cleaningMode": "\\u5c18\\u63a8",
              "taskEndStatus": 0,
              "subTasks": [
                { "mapId": "27a4dcf5-0301-46e8-9100-cb3511688694", "mapName": "SPD_3",
                  "actualCleaningAreaSquareMeter": 2411.293, "taskId": "" }
              ],
              "startTime": "2026-07-19T16:29:08Z",
              "endTime": "2026-07-19T19:28:36Z"
            }
            """;

    @Test
    void subTasksAreParsedFromTheLiveResponseShape() throws Exception {
        GausiumTaskReport report = objectMapper.readValue(LIVE_REPORT_JSON, GausiumTaskReport.class);

        assertThat(report.areaNameList()).isEmpty();
        assertThat(report.subTasks()).hasSize(1);
        assertThat(report.subTasks().get(0).mapName()).isEqualTo("SPD_3");
    }

    @Test
    void mapNameFallsBackToTheSubTaskMapWhenAreaNameListIsEmpty() throws Exception {
        GausiumTaskReport report = objectMapper.readValue(LIVE_REPORT_JSON, GausiumTaskReport.class);

        assertThat(mapNameOf(report)).isEqualTo("SPD_3");
    }

    @Test
    void areaNameListWinsWhenGausiumPopulatesIt() throws Exception {
        GausiumTaskReport report = objectMapper.readValue(
                LIVE_REPORT_JSON.replace("\"areaNameList\": \"\"", "\"areaNameList\": \"Zone A, Zone B\""),
                GausiumTaskReport.class);

        assertThat(mapNameOf(report)).isEqualTo("Zone A, Zone B");
    }

    @Test
    void mapNameIsNullWhenNeitherSourceHasOne() throws Exception {
        GausiumTaskReport report = objectMapper.readValue(
                LIVE_REPORT_JSON.replaceAll("\"subTasks\"\\s*:\\s*\\[[^]]*],", ""),
                GausiumTaskReport.class);

        assertThat(report.subTasks()).isNull();
        assertThat(mapNameOf(report)).isNull();
    }

    private String mapNameOf(GausiumTaskReport report) {
        return GausiumAdapter.resolveMapName(report);
    }
}
