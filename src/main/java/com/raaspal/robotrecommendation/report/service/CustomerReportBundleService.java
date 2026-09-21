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
import com.raaspal.robotrecommendation.robotunit.service.RobotUnitService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aggregates every robot deployed to a customer into one monthly report
 * bundle, so a customer with several robots gets a single combined report
 * (and a single email) instead of one per robot.
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
        Set<UUID> excluded = excludedRobotUnitIds(customerProfileId, month);

        // Each robot's report is served from cache (computed once per robot+month).
        List<ReportPreviewResponse> robots = robotUnitService.listByCustomer(customerProfileId).stream()
                .filter(CustomerReportBundleService::inCleaningReport)
                .filter(robot -> underContract(robot, month))
                .filter(robot -> !excluded.contains(robot.id()))
                .map(robot -> reportCacheService.getRobotReport(robot.serialNumber(), month))
                .toList();

        return new CustomerReportBundleResponse(customer.getCompanyName(), periodLabel(month), robots);
    }

    /**
     * What staff review before sending: every deployed robot, including the ones
     * currently held back, each flagged with whether it logged any activity.
     */
    @Transactional(readOnly = true)
    public CustomerBundlePreviewResponse buildPreview(UUID customerProfileId, String month) {
        CustomerProfile customer = require(customerProfileId);
        Set<UUID> excluded = excludedRobotUnitIds(customerProfileId, month);

        List<CustomerBundlePreviewResponse.Robot> robots = robotUnitService.listByCustomer(customerProfileId).stream()
                .filter(CustomerReportBundleService::inCleaningReport)
                .filter(robot -> underContract(robot, month))
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
                customerProfileId, customer.getCompanyName(), periodLabel(month), month, included, robots);
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
    private static boolean underContract(RobotUnitResponse robot, String month) {
        if (robot.deployment() == null) return true;
        return ReportPeriod.ofMonth(month).coversContract(
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

    private String periodLabel(String month) {
        try {
            YearMonth ym = YearMonth.parse(month);
            return Month.of(ym.getMonthValue()).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear();
        } catch (Exception e) {
            return month;
        }
    }
}
