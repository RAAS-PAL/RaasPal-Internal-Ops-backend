package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.report.dto.CustomerReportBundleResponse;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse;
import com.raaspal.robotrecommendation.robotunit.service.RobotUnitService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Aggregates every robot deployed to a customer into one monthly report
 * bundle, so a customer with several robots gets a single combined report
 * (and a single email) instead of one per robot.
 */
@Service
@RequiredArgsConstructor
public class CustomerReportBundleService {

    private final CustomerProfileRepository customerProfileRepository;
    private final RobotUnitService robotUnitService;
    private final ReportPreviewService reportPreviewService;

    @Transactional(readOnly = true)
    public CustomerReportBundleResponse build(UUID customerProfileId, String month) {
        CustomerProfile customer = customerProfileRepository.findById(customerProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("CustomerProfile", "id", customerProfileId));

        List<ReportPreviewResponse> robots = robotUnitService.listByCustomer(customerProfileId).stream()
                .map(robot -> reportPreviewService.build(robot.serialNumber(), month))
                .toList();

        return new CustomerReportBundleResponse(customer.getCompanyName(), periodLabel(month), robots);
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
