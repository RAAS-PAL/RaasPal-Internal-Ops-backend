package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.report.dto.CustomerBundlePreviewResponse;
import com.raaspal.robotrecommendation.report.dto.CustomerReportBundleResponse;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse;
import com.raaspal.robotrecommendation.report.entity.CustomerReportExclusion;
import com.raaspal.robotrecommendation.report.repository.CustomerReportExclusionRepository;
import com.raaspal.robotrecommendation.robotunit.dto.RobotUnitResponse;
import com.raaspal.robotrecommendation.robotunit.entity.ReportCadence;
import com.raaspal.robotrecommendation.robotunit.service.RobotUnitService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aggregates every robot deployed to a customer into one report bundle for a
 * period, so a customer with several robots gets a single combined report
 * (and a single email) instead of one per robot.
 *
 * <p>The period is a key: a month ("2026-08") or an ISO week ("2026-W38"). A
 * monthly bundle holds every cleaning robot the customer has under contract, as
 * it always has. A weekly bundle holds only the robots set to {@code WEEKLY}:
 * weekly reporting is opted into robot by robot, and the customer's other robots
 * were never asked for it.
 *
 * <p>Robots the customer success team has held back for the month are dropped
 * here rather than in the UI, so the customer's public link and the staff
 * preview cannot disagree about what was sent.
 */
@Service
@RequiredArgsConstructor
public class CustomerReportBundleService {

    private final CustomerProfileRepository customerProfileRepository;
    private final RobotUnitService robotUnitService;
    private final ReportCacheService reportCacheService;
    private final CustomerReportExclusionRepository exclusionRepository;

    /**
     * Whether a robot belongs in this (cleaning) bundle. Delivery robots - AutoXing today -
     * have no cleaning telemetry, so the Gausium-shaped report built for them would be an
     * empty page with the customer's name on it. They are left out until the delivery
     * report is part of the bundle.
     */
    static boolean inCleaningReport(RobotUnitResponse robot) {
        return robot.robotType() != RobotType.DELIVERY;
    }

    /** What the customer sees: the reports actually being sent, excluded robots omitted. */
    @Transactional(readOnly = true)
    public CustomerReportBundleResponse build(UUID customerProfileId, String month) {
        CustomerProfile customer = require(customerProfileId);
        ReportPeriod period = ReportPeriod.parse(month);
        Set<UUID> excluded = excludedRobotUnitIds(customerProfileId, month);

        // Each robot's report is served from cache (computed once per robot+period).
        List<ReportPreviewResponse> robots = robotUnitService.listByCustomer(customerProfileId).stream()
                .filter(CustomerReportBundleService::inCleaningReport)
                .filter(robot -> inPeriodBundle(robot, period))
                .filter(robot -> underContract(robot, period))
                .filter(robot -> !excluded.contains(robot.id()))
                .map(robot -> reportCacheService.getRobotReport(robot.serialNumber(), month))
                .toList();

        return new CustomerReportBundleResponse(customer.getCompanyName(), period.label(), robots);
    }

    /**
     * What staff review before sending: every deployed robot, including the ones
     * currently held back, each flagged with whether it logged any activity.
     */
    @Transactional(readOnly = true)
    public CustomerBundlePreviewResponse buildPreview(UUID customerProfileId, String month) {
        CustomerProfile customer = require(customerProfileId);
        ReportPeriod period = ReportPeriod.parse(month);
        Set<UUID> excluded = excludedRobotUnitIds(customerProfileId, month);

        List<CustomerBundlePreviewResponse.Robot> robots = robotUnitService.listByCustomer(customerProfileId).stream()
                .filter(CustomerReportBundleService::inCleaningReport)
                .filter(robot -> inPeriodBundle(robot, period))
                .filter(robot -> underContract(robot, period))
                .map(robot -> {
                    ReportPreviewResponse report = reportCacheService.getRobotReport(robot.serialNumber(), month);
                    return new CustomerBundlePreviewResponse.Robot(
                            robot.id(),
                            robot.serialNumber(),
                            report.robotName(),
                            site(robot),
                            hasData(report),
                            excluded.contains(robot.id()),
                            report);
                })
                .toList();

        int included = (int) robots.stream().filter(r -> !r.excluded()).count();
        return new CustomerBundlePreviewResponse(
                customerProfileId, customer.getCompanyName(), period.label(), month, included, robots);
    }

    /**
     * Whether a robot belongs in this period's bundle. Every robot is in a monthly
     * bundle, exactly as before weekly existed. A weekly bundle takes only the robots
     * set to {@code WEEKLY}, since that setting is how a robot is opted into it.
     */
    static boolean inPeriodBundle(RobotUnitResponse robot, ReportPeriod period) {
        if (period.type() != ReportPeriod.Type.WEEK) return true;
        return robot.deployment() != null && robot.deployment().reportCadence() == ReportCadence.WEEKLY;
    }

    /**
     * A robot with no completed tasks logged nothing that month — almost always
     * because it was offline. Its report still renders, as a page of zeros, which
     * reads as a broken report rather than an accurate one; the UI uses this to
     * offer excluding it.
     */
    private static boolean hasData(ReportPreviewResponse report) {
        return report.executive() != null && report.executive().totalTasksCompleted() > 0;
    }

    private static String site(RobotUnitResponse robot) {
        return robot.deployment() != null ? robot.deployment().site() : "—";
    }

    /**
     * Whether the robot was the customer's at any point in the month. A robot whose
     * contract ended before the month began is not on the bundle and not in the
     * review list — it is no longer theirs, and a page of zeros for it would read as a
     * fault rather than a fact. The final partial month is still sent, clipped to the
     * end date by {@link ReportPreviewService}. Null dates are unbounded, as before.
     */
    private static boolean underContract(RobotUnitResponse robot, ReportPeriod period) {
        if (robot.deployment() == null) return true;
        return period.coversContract(
                robot.deployment().contractStartDate(), robot.deployment().contractEndDate());
    }

    private Set<UUID> excludedRobotUnitIds(UUID customerProfileId, String month) {
        return exclusionRepository.findAllByCustomerProfileIdAndReportMonth(customerProfileId, month).stream()
                .map(CustomerReportExclusion::getRobotUnitId)
                .collect(Collectors.toSet());
    }

    private CustomerProfile require(UUID customerProfileId) {
        return customerProfileRepository.findById(customerProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("CustomerProfile", "id", customerProfileId));
    }
}
