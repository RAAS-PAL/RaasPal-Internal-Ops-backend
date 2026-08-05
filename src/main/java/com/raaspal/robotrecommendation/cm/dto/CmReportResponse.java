package com.raaspal.robotrecommendation.cm.dto;

import com.raaspal.robotrecommendation.cm.entity.CmReport;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record CmReportResponse(
        UUID id,
        LocalDate reportDate,
        String ticketNo,
        String customerName,
        String technicianName,
        String robotModel,
        String serialNumber,
        String causeDetail,
        String inspectionResult,
        String correctiveActions,
        String testResult,
        String sourceText,
        String providerSignature,
        String receiverSignature,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static CmReportResponse from(CmReport r) {
        return new CmReportResponse(
                r.getId(),
                r.getReportDate(),
                r.getTicketNo(),
                r.getCustomerName(),
                r.getTechnicianName(),
                r.getRobotModel(),
                r.getSerialNumber(),
                r.getCauseDetail(),
                r.getInspectionResult(),
                r.getCorrectiveActions(),
                r.getTestResult(),
                r.getSourceText(),
                r.getProviderSignature(),
                r.getReceiverSignature(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    /**
     * Summary projection for the history list — drops the signature data URIs and
     * the original paste, which together dwarf every other field and are never read
     * until a specific report is opened.
     */
    public static CmReportResponse summaryFrom(CmReport r) {
        return new CmReportResponse(
                r.getId(),
                r.getReportDate(),
                r.getTicketNo(),
                r.getCustomerName(),
                r.getTechnicianName(),
                r.getRobotModel(),
                r.getSerialNumber(),
                r.getCauseDetail(),
                r.getInspectionResult(),
                r.getCorrectiveActions(),
                r.getTestResult(),
                null,
                null,
                null,
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
