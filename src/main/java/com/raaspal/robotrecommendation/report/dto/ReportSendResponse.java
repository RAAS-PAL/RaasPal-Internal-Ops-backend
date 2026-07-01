package com.raaspal.robotrecommendation.report.dto;

import com.raaspal.robotrecommendation.report.entity.ReportSend;

import java.time.LocalDateTime;
import java.util.UUID;

/** One row of report delivery history, enriched with the customer's name. */
public record ReportSendResponse(
        UUID id,
        UUID customerProfileId,
        String customerName,
        String reportMonth,
        String status,
        String recipientEmail,
        String errorMessage,
        LocalDateTime sentAt) {

    public static ReportSendResponse of(ReportSend send, String customerName) {
        return new ReportSendResponse(
                send.getId(),
                send.getCustomerProfileId(),
                customerName,
                send.getReportMonth(),
                send.getStatus().name(),
                send.getRecipientEmail(),
                send.getErrorMessage(),
                send.getSentAt());
    }
}
