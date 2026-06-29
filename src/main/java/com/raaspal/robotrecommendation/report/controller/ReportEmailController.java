package com.raaspal.robotrecommendation.report.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.report.service.ReportEmailService;
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

    private final ReportEmailService reportEmailService;

    @PostMapping("/email")
    public ApiResponse<SentEmail> sendEmail(
            @RequestParam String serialNumber,
            @RequestParam String month) {
        SentEmail sent = reportEmailService.send(serialNumber, month);
        return ApiResponse.success("Report email sent to " + sent.recipient(), sent);
    }
}
