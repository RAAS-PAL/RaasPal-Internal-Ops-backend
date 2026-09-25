package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse.Consumable;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse.Executive;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse.Operational;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse.Recommendation;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse.Ring;
import com.raaspal.robotrecommendation.robotunit.dto.RobotUnitResponse;
import com.raaspal.robotrecommendation.robotunit.service.RobotUnitService;
import com.raaspal.robotrecommendation.telemetry.core.CleaningModeLabels;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import com.raaspal.robotrecommendation.telemetry.repository.RobotTaskReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregates a robot's synced task reports for a {@link ReportPeriod} — a calendar
 * month or an ISO week — into the web report's {@link ReportPreviewResponse}.
 * Read-only; uses the same telemetry the Excel generator does, but produces
 * summary metrics for the report page.
 */
@Service
@RequiredArgsConstructor
public class ReportPreviewService {

    private final RobotUnitService robotUnitService;
    private final RobotTaskReportRepository reportRepository;

    /**
     * Timezone the contract start date is interpreted in. A contract begins on a
     * calendar day in the business's own timezone, not at a UTC instant — and the
     * difference is not academic here, because cleaning robots routinely run
     * overnight: 02:00 in Bangkok on the start date is still the previous day in UTC,
     * and those tasks belong to the customer.
     */
    @Value("${app.reports.business-zone:Asia/Bangkok}")
    private String businessZone;

    /**
     * Builds the report for a robot (by serial number) and a month ("YYYY-MM").
     * Returns zeroed metrics with an explanatory recommendation when the robot
     * has no synced tasks for that month.
     */
    @Transactional(readOnly = true)
    public ReportPreviewResponse build(String serialNumber, String month) {
        return build(serialNumber, ReportPeriod.ofMonth(month));
    }

    /**
     * Builds the report for a robot and an ISO week ("YYYY-Www", Monday–Sunday).
     * Same aggregation as the monthly report over a shorter window — the figures
     * mean the same thing, so a week's numbers reconcile with the month's.
     */
    @Transactional(readOnly = true)
    public ReportPreviewResponse buildForWeek(String serialNumber, String week) {
        return build(serialNumber, ReportPeriod.ofWeek(week));
    }

    /**
     * Builds the report for a robot and any {@link ReportPeriod} — the entry point for
     * callers holding a stored period key (report links, the report cache), which may
     * be either a month or a week.
     */
    @Transactional(readOnly = true)
    public ReportPreviewResponse build(String serialNumber, ReportPeriod period) {
        RobotUnitResponse robot = robotUnitService.getBySerialNumber(serialNumber);
        List<RobotTaskReport> reports = load(robot, period);
        reports = clipToContract(reports, robot, period);

        String customerName = robot.deployment() != null ? robot.deployment().customerName() : "Unassigned customer";
        String site = robot.deployment() != null ? robot.deployment().site() : "—";
        String robotName = robot.name() != null && !robot.name().isBlank()
                ? robot.name()
                : (robot.brand() + (robot.model() != null ? " " + robot.model() : "")).trim();

        if (reports.isEmpty()) {
            return empty(robot, customerName, site, robotName, period);
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
                // Area cleaned per 100% of battery = (area / battery% used) × 100.
                batteryUsed > 0 ? String.format(Locale.US, "%,.1f sqm/100%%", totalArea / batteryUsed * 100) : "—");

        Operational operational = new Operational(
                List.of(
                        new Ring("Task Completion Rate", round2(avgCompletion)),
                        new Ring("Cleaning Coverage Rate", round2(coverage))),
                taskTypeBreakdown(reports),
                taskStatusBreakdown(reports),
                activeDays > 0 ? String.format(Locale.US, "%.1f", (double) taskCount / activeDays) : "—",
                formatMinSec(taskCount > 0 ? totalSeconds / taskCount : 0));

        RobotTaskReport latest = reports.stream()
                .filter(r -> r.getStartTime() != null)
                .max(Comparator.comparing(RobotTaskReport::getStartTime))
                .orElse(reports.get(reports.size() - 1));
        List<Consumable> consumables = consumables(latest);

        return new ReportPreviewResponse(
                robot.brand(), customerName, site, robotName, robot.serialNumber(),
                period.label(), executive, operational, consumables,
                recommendations(avgCompletion, consumables));
    }

    /* ─── Helpers ────────────────────────────────────────────────────────── */

    /**
     * The task reports this period covers. A month reads the stored, indexed
     * {@code reportMonth}; a week has to be a start-time range, since it can span
     * two of them.
     */
    private List<RobotTaskReport> load(RobotUnitResponse robot, ReportPeriod period) {
        return switch (period.type()) {
            case MONTH -> reportRepository.findByRobotUnitIdAndReportMonth(robot.id(), period.key());
            case WEEK -> reportRepository.findByRobotUnitIdAndStartTimeBetween(
                    robot.id(), period.startInstant(zone()), period.endInstant(zone()));
        };
    }

    private ReportPreviewResponse empty(RobotUnitResponse robot, String customerName, String site, String robotName, ReportPeriod period) {
        return new ReportPreviewResponse(
                robot.brand(), customerName, site, robotName, robot.serialNumber(),
                period.label(),
                new Executive(0, "0 h 0 min 0 sec", 0, 0, 0, "—"),
                new Operational(
                        List.of(new Ring("Task Completion Rate", 0), new Ring("Cleaning Coverage Rate", 0)),
                        "—", "—", "—", "0 min 0 sec"),
                List.of(),
                List.of(new Recommendation("noData", null, null)));
    }

    private List<Consumable> consumables(RobotTaskReport r) {
        List<Consumable> list = new ArrayList<>();
        addConsumable(list, "Brush", r.getBrushResidualPct());
        addConsumable(list, "Filter", r.getFilterResidualPct());
        addConsumable(list, "Squeegee", r.getSuctionBladeResidualPct());
        return list;
    }

    /**
     * Adds a consumable only when the robot actually has it. A residual of null —
     * or exactly 0 — means the robot doesn't carry/report that part (e.g. a
     * sweeper has no brush/filter/squeegee; Gausium fills those with 0), so we
     * omit it rather than show a misleading "0% — replace now". A genuinely worn
     * part reports a small positive value (e.g. 13.7%), which is kept.
     */
    private void addConsumable(List<Consumable> list, String label, BigDecimal residual) {
        if (residual != null && residual.signum() > 0) {
            list.add(consumable(label, residual));
        }
    }

    private Consumable consumable(String label, BigDecimal residual) {
        double pct = residual.doubleValue();
        String state = pct >= 80 ? "good" : pct >= 50 ? "monitor" : "action";
        return new Consumable(label, round2(pct), state);
    }

    private List<Recommendation> recommendations(double avgCompletion, List<Consumable> consumables) {
        List<Recommendation> recs = new ArrayList<>();
        for (Consumable c : consumables) {
            if ("action".equals(c.state())) {
                recs.add(new Recommendation("action", c.label(), c.percent()));
            } else if ("monitor".equals(c.state())) {
                recs.add(new Recommendation("monitor", c.label(), c.percent()));
            }
        }
        if (avgCompletion < 80) {
            recs.add(new Recommendation("completion", null, round2(avgCompletion)));
        }
        if (recs.isEmpty()) {
            recs.add(new Recommendation("healthy", null, null));
        }
        return recs;
    }

    /**
     * Every task-end status in the period with its count, most-frequent first,
     * e.g. "Completed ×8, Abnormal termination ×2". A missing status counts as
     * "Completed" (a finished task that reported no explicit end status).
     */
    private static String taskStatusBreakdown(List<RobotTaskReport> reports) {
        Map<String, Long> counts = reports.stream()
                .collect(Collectors.groupingBy(r -> statusLabel(r.getTaskEndStatus()), Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getKey() + " ×" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String statusLabel(Integer status) {
        if (status == null) return "Completed";
        return switch (status) {
            case 0 -> "Completed";
            case 1 -> "Manually terminated";
            case 2 -> "Abnormal termination";
            case 3 -> "Startup failure";
            default -> "Unknown";
        };
    }

    /**
     * Drops tasks performed outside this robot's contract with the customer.
     * <p>
     * Without the start clip a robot deployed on the 15th reported a full "July"
     * including two weeks of work done before the customer had it — real numbers, but
     * not theirs. The end clip is the mirror: a robot whose contract ended on the 15th
     * must not report the fortnight it spent working for whoever had it next. Only the
     * period containing a date is affected; a period wholly inside the contract passes
     * through untouched, and one wholly outside it comes back empty.
     * <p>
     * Per deployment rather than per customer: one customer commonly takes on robots
     * at different times across different sites.
     * <p>
     * A null date on either side (the default for existing deployments) does not clip
     * that side, exactly as before.
     */
    private List<RobotTaskReport> clipToContract(
            List<RobotTaskReport> reports, RobotUnitResponse robot, ReportPeriod period) {
        Instant from = contractStartInstant(robot, period);
        Instant until = contractEndInstant(robot, period);
        if (from == null && until == null) return reports;
        return reports.stream()
                .filter(r -> r.getStartTime() != null)
                .filter(r -> from == null || !r.getStartTime().isBefore(from))
                .filter(r -> until == null || !r.getStartTime().isAfter(until))
                .toList();
    }

    /**
     * The last instant of this deployment's contract, or null when no clipping applies —
     * either because no end date is recorded, or because it falls after the period.
     * Inclusive: the end date is the customer's last day, so 23:59:59 on it counts.
     */
    private Instant contractEndInstant(RobotUnitResponse robot, ReportPeriod period) {
        if (robot.deployment() == null) return null;
        LocalDate contractEnd = robot.deployment().contractEndDate();
        if (contractEnd == null) return null;
        if (period.endDate() == null) return null;
        // An end on or after the last day of the period clips nothing.
        if (!contractEnd.isBefore(period.endDate())) return null;
        return contractEnd.atTime(LocalTime.MAX).atZone(zone()).toInstant();
    }

    /**
     * The instant this deployment's contract starts, or null when no clipping applies —
     * either because no start date is recorded, or because it precedes the period.
     */
    private Instant contractStartInstant(RobotUnitResponse robot, ReportPeriod period) {
        if (robot.deployment() == null) return null;
        LocalDate contractStart = robot.deployment().contractStartDate();
        if (contractStart == null) return null;
        // An unparseable month has no start date to compare against; it matches no
        // stored report anyway, so there is nothing to clip.
        if (period.startDate() == null) return null;
        // A start on or before the first day of the period clips nothing.
        if (!contractStart.isAfter(period.startDate())) return null;
        return contractStart.atStartOfDay(zone()).toInstant();
    }

    private ZoneId zone() {
        return ZoneId.of(businessZone);
    }

    /**
     * Every cleaning mode used in the period with its run count, most-frequent
     * first, e.g. "Wet Mopping ×7, Mopping ×3". "—" when no mode is recorded.
     */
    private static String taskTypeBreakdown(List<RobotTaskReport> reports) {
        // Group by the mapped English label (not the raw mode), so raw values that
        // translate to the same label — or both fall back to "Other" — merge into one count.
        Map<String, Long> counts = reports.stream()
                .map(RobotTaskReport::getCleaningMode)
                .filter(m -> m != null && !m.isBlank())
                .collect(Collectors.groupingBy(ReportPreviewService::modeLabel, Collectors.counting()));
        if (counts.isEmpty()) return "—";
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getKey() + " ×" + e.getValue()) // keys are already English labels
                .collect(Collectors.joining(", "));
    }

    /**
     * Maps a raw mode code (English or Chinese) to a friendly English label.
     * Delegates to {@link CleaningModeLabels} so this report and the partner API
     * describe the same task identically — a customer and their service partner
     * comparing notes must not see two different words for one cleaning mode.
     */
    private static String modeLabel(String mode) {
        return CleaningModeLabels.toEnglish(mode);
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
