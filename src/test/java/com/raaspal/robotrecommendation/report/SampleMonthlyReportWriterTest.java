package com.raaspal.robotrecommendation.report;

import com.raaspal.robotrecommendation.report.generators.gausium.GausiumReportGenerator;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Utility "test" that writes a real {@code .xlsx} sample of the monthly report
 * to {@code docs/sample-monthly-report.xlsx} so the team can open it and verify
 * the format — no Supabase / n8n / LINE setup required.
 *
 * <p>Disabled during normal builds. Generate the sample explicitly with:
 * <pre>
 *   mvnw.cmd test -Dtest=SampleMonthlyReportWriterTest -DwriteSampleReport=true
 * </pre>
 * The file mirrors one robot's month (3 cleaning tasks = 3 data rows under the
 * 28-column "task-queue-list" header), which is exactly what production emits
 * per robot.
 */
@EnabledIfSystemProperty(named = "writeSampleReport", matches = "true")
class SampleMonthlyReportWriterTest {

    @Test
    void writeSampleReport() throws Exception {
        RobotUnit robot = RobotUnit.builder()
                .serialNumber("GS401-TEST-0001")
                .brand("GAUSIUM")
                .model("Scrubber 50 Pro")
                .name("Test Robot — 8th Floor")
                .build();

        List<RobotTaskReport> reports = List.of(
                row(robot, "2026-05-03T01:00:00Z", "2026-05-03T01:14:56Z", "Zone-1",
                        "86.60", "146.31", 727, "126.67", "626.77", "2.29", 65, 59,
                        "99.92", "100.00", "100.00", "report1"),
                row(robot, "2026-05-10T02:00:00Z", "2026-05-10T02:20:30Z", "Zone-2",
                        "92.10", "200.30", 1230, "184.50", "540.20", "3.10", 80, 71,
                        "98.40", "99.50", "99.00", "report2"),
                row(robot, "2026-05-21T01:30:00Z", "2026-05-21T01:40:10Z", "Zone-1",
                        "78.30", "90.00", 610, "70.40", "410.00", "1.80", 55, 49,
                        "97.10", "98.20", "97.80", "report3"));

        byte[] xlsx = new GausiumReportGenerator().generate(reports);

        Path out = Path.of("docs", "sample-monthly-report.xlsx");
        Files.createDirectories(out.getParent());
        Files.write(out, xlsx);

        System.out.println("Sample monthly report written to: " + out.toAbsolutePath()
                + " (" + xlsx.length + " bytes)");
    }

    private static RobotTaskReport row(RobotUnit robot, String start, String end, String plan,
                                       String completion, String plannedArea, int seconds,
                                       String cleaningArea, String efficiency, String water,
                                       int startBattery, int endBattery,
                                       String brush, String filter, String squeegee, String pngId) {
        return RobotTaskReport.builder()
                .robotUnit(robot)
                .brand("GAUSIUM")
                .startTime(Instant.parse(start))
                .endTime(Instant.parse(end))
                .mapName("MCR_Floor-8")
                .cleaningPlan(plan)
                .taskCompletionPct(new BigDecimal(completion))
                .plannedAreaSqm(new BigDecimal(plannedArea))
                .workingTimeSeconds(seconds)
                .cleaningAreaSqm(new BigDecimal(cleaningArea))
                .workEfficiencySqmH(new BigDecimal(efficiency))
                .waterConsumptionL(new BigDecimal(water))
                .startBatteryPct(startBattery)
                .endBatteryPct(endBattery)
                .brushResidualPct(new BigDecimal(brush))
                .filterResidualPct(new BigDecimal(filter))
                .suctionBladeResidualPct(new BigDecimal(squeegee))
                .cleaningMode("Floor Washing")
                .taskReportPngUri("https://example.com/" + pngId + ".png")
                .build();
    }
}
