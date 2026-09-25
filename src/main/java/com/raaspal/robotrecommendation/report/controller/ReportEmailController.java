package com.raaspal.robotrecommendation.report.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.report.service.ReportDeliveryService;
import com.raaspal.robotrecommendation.report.service.ReportEmailService.SentEmail;
import com.raaspal.robotrecommendation.report.service.ReportPeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Emails a robot's monthly or weekly report link to its customer. Authenticated (staff
 * action); the customer opens the public link without an account.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportEmailController {

    private final ReportDeliveryService reportDeliveryService;

    /**
     * One robot's report to its customer — the Report preview tab's Send. Goes
     * through the delivery service rather than the email service directly so the
     * send is recorded in Delivery history like every other email to a customer.
     * Takes exactly one of {@code month} ("YYYY-MM") or {@code week} ("YYYY-Www").
     */
    @PostMapping("/email")
    public ApiResponse<SentEmail> sendEmail(
            @RequestParam String serialNumber,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String week) {
        SentEmail sent = reportDeliveryService.sendRobotReport(serialNumber, ReportPeriod.fromRequest(month, week));
        return ApiResponse.success("Report email sent to " + sent.recipient(), sent);
    }
}
