package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse.Consumable;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse.Executive;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse.Operational;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse.Ring;
import com.raaspal.robotrecommendation.robotunit.dto.RobotUnitResponse;
import com.raaspal.robotrecommendation.robotunit.service.RobotUnitService;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import com.raaspal.robotrecommendation.telemetry.repository.RobotTaskReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregates a robot's synced task reports for a month into the web report's
 * {@link ReportPreviewResponse}. Read-only; uses the same telemetry the Excel
 * generator does, but produces summary metrics for the report page.
 */
@Service
@RequiredArgsConstructor
public class ReportPreviewService {

    /** Standard red "customer needs to know" questions shown on every report. */
    private static final List<String> CUSTOMER_QUESTIONS = List.of(
            "Robot ทำงานคุ้ม ?",
            "ช่วยคนทำงานได้ ?",
            "ทำงานเร็ว ?",
            "มีปัญหา ?");

    private final RobotUnitService robotUnitService;
    private final RobotTaskReportRepository reportRepository;

    /**
     * Builds the report for a robot (by serial number) and a month ("YYYY-MM").
     * Returns zeroed metrics with an explanatory recommendation when the robot
     * has no synced tasks for that month.
     */
    @Transactional(readOnly = true)
    public ReportPreviewResponse build(String serialNumber, String month) {
        RobotUnitResponse robot = robotUnitService.getBySerialNumber(serialNumber);
        List<RobotTaskReport> reports = reportRepository.findByRobotUnitIdAndReportMonth(robot.id(), month);

        String customerName = robot.deployment() != null ? robot.deployment().customerName() : "Unassigned customer";
        String site = robot.deployment() != null ? robot.deployment().site() : "—";
        String robotName = robot.name() != null && !robot.name().isBlank()
                ? robot.name()
                : (robot.brand() + (robot.model() != null ? " " + robot.model() : "")).trim();

        if (reports.isEmpty()) {
            return empty(robot, customerName, site, robotName, month);
        }

        int taskCount = reports.size();
        long totalSeconds = reports.stream().mapToLong(r -> nz(r.getWorkingTimeSeconds())).sum();
        double totalArea = reports.stream().mapToDouble(r -> d(r.getCleaningAreaSqm())).sum();
        double totalPlanned = reports.stream().mapToDouble(r -> d(r.getPlannedAreaSqm())).sum();
        double totalWater = reports.stream().mapToDouble(r -> d(r.getWaterConsumptionL())).sum();
        double batteryUsed = reports.stream()
                .mapToDouble(r -> Math.max(0, nz(r.getStartBatteryPct()) - nz(r.getEndBatteryPct())))
                .sum();
        double avgCompletion = reports.stream().mapToDouble(r -> d(r.getTaskCompletionPct())).average().orElse(0);
        double coverage = totalPlanned > 0 ? (totalArea / totalPlanned) * 100 : 0;
        double productivity = totalSeconds > 0 ? totalArea / (totalSeconds / 3600.0) : 0;
        long activeDays = reports.stream()
                .filter(r -> r.getStartTime() != null)
                .map(r -> r.getStartTime().atZone(ZoneOffset.UTC).toLocalDate())
                .distinct().count();

        Executive executive = new Executive(
                taskCount,
                formatHms(totalSeconds),
                round2(totalArea),
                round2(productivity),
                round2(totalWater),
                totalArea > 0 ? String.format(Locale.US, "%.1f %%/100 sqm", batteryUsed / totalArea * 100) : "—");

        Operational operational = new Operational(
                List.of(
                        new Ring("Task Completion Rate", round2(avgCompletion)),
                        new Ring("Cleaning Coverage Rate", round2(coverage))),
                mostCommon(reports.stream().map(RobotTaskReport::getCleaningMode).filter(s -> s != null && !s.isBlank()).toList(), "—"),
                taskStatus(reports),
                activeDays > 0 ? String.format(Locale.US, "%.1f", (double) taskCount / activeDays) : "—",
                formatMinSec(taskCount > 0 ? totalSeconds / taskCount : 0));

        RobotTaskReport latest = reports.stream()
                .filter(r -> r.getStartTime() != null)
                .max(Comparator.comparing(RobotTaskReport::getStartTime))
                .orElse(reports.get(reports.size() - 1));
        List<Consumable> consumables = consumables(latest);

        return new ReportPreviewResponse(
                robot.brand(), customerName, site, robotName, robot.serialNumber(),
                periodLabel(month), CUSTOMER_QUESTIONS, executive, operational, consumables,
                recommendations(avgCompletion, consumables));
    }

    /* ─── Helpers ────────────────────────────────────────────────────────── */

    private ReportPreviewResponse empty(RobotUnitResponse robot, String customerName, String site, String robotName, String month) {
        return new ReportPreviewResponse(
                robot.brand(), customerName, site, robotName, robot.serialNumber(),
                periodLabel(month), CUSTOMER_QUESTIONS,
                new Executive(0, "0 h 0 min 0 sec", 0, 0, 0, "—"),
                new Operational(
                        List.of(new Ring("Task Completion Rate", 0), new Ring("Cleaning Coverage Rate", 0)),
                        "—", "—", "—", "0 min 0 sec"),
                List.of(),
                List.of("No task data found for this robot in " + periodLabel(month)
                        + ". Connect Gausium telemetry or choose a month with synced tasks."));
    }

    private List<Consumable> consumables(RobotTaskReport r) {
        List<Consumable> list = new ArrayList<>();
        if (r.getBrushResidualPct() != null) list.add(consumable("Brush", r.getBrushResidualPct()));
        if (r.getFilterResidualPct() != null) list.add(consumable("Filter", r.getFilterResidualPct()));
        if (r.getSuctionBladeResidualPct() != null) list.add(consumable("Squeegee", r.getSuctionBladeResidualPct()));
        return list;
    }

    private Consumable consumable(String label, BigDecimal residual) {
        double pct = residual.doubleValue();
        String state = pct >= 80 ? "good" : pct >= 50 ? "monitor" : "action";
        return new Consumable(label, round2(pct), state);
    }

    private List<String> recommendations(double avgCompletion, List<Consumable> consumables) {
        List<String> recs = new ArrayList<>();
        for (Consumable c : consumables) {
            if ("action".equals(c.state())) {
                recs.add("Schedule " + c.label().toLowerCase() + " replacement soon — residual at " + c.percent() + "%.");
            } else if ("monitor".equals(c.state())) {
                recs.add("Monitor " + c.label().toLowerCase() + " wear — residual at " + c.percent() + "%.");
            }
        }
        if (avgCompletion < 80) {
            recs.add(String.format(Locale.US, "Investigate incomplete tasks — average completion is %.1f%%.", avgCompletion));
        }
        if (recs.isEmpty()) {
            recs.add("Continue the current operation plan — performance is within a healthy range.");
        }
        return recs;
    }

    private String taskStatus(List<RobotTaskReport> reports) {
        Map<Integer, Long> counts = reports.stream()
                .filter(r -> r.getTaskEndStatus() != null)
                .collect(Collectors.groupingBy(RobotTaskReport::getTaskEndStatus, Collectors.counting()));
        if (counts.isEmpty()) return "Completed";
        int top = counts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        return switch (top) {
            case 0 -> "Completed";
            case 1 -> "Manually terminated";
            case 2 -> "Abnormal termination";
            case 3 -> "Startup failure";
            default -> "—";
        };
    }

    private String periodLabel(String month) {
        try {
            YearMonth ym = YearMonth.parse(month);
            return Month.of(ym.getMonthValue()).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear();
        } catch (Exception e) {
            return month;
        }
    }

    private static String mostCommon(List<String> values, String fallback) {
        return values.stream()
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(fallback);
    }

    private static String formatHms(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h + " h " + m + " min " + s + " sec";
    }

    private static String formatMinSec(long seconds) {
        return (seconds / 60) + " min " + String.format(Locale.US, "%02d", seconds % 60) + " sec";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double d(BigDecimal b) {
        return b == null ? 0 : b.doubleValue();
    }

    private static long nz(Integer i) {
        return i == null ? 0 : i;
    }
}
