package com.raaspal.robotrecommendation.report.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.report.service.ReportDeliveryService;
import com.raaspal.robotrecommendation.report.service.ReportEmailService.SentEmail;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Emails a robot's monthly report link to its customer. Authenticated (staff
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
     */
    @PostMapping("/email")
    public ApiResponse<SentEmail> sendEmail(
            @RequestParam String serialNumber,
            @RequestParam String month) {
        SentEmail sent = reportDeliveryService.sendRobotReport(serialNumber, month);
        return ApiResponse.success("Report email sent to " + sent.recipient(), sent);
    }
}
