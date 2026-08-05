package com.raaspal.robotrecommendation.cm.service;

import com.raaspal.robotrecommendation.ai.dto.CmReportDraft;
import com.raaspal.robotrecommendation.ai.service.CmReportExtractionService;
import com.raaspal.robotrecommendation.cm.dto.CmReportRequest;
import com.raaspal.robotrecommendation.cm.dto.CmReportResponse;
import com.raaspal.robotrecommendation.cm.entity.CmReport;
import com.raaspal.robotrecommendation.cm.repository.CmReportRepository;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Corrective Maintenance reports: AI-assisted parsing of a pasted service ticket,
 * plus CRUD over the reviewed result.
 * <p>
 * Parsing and saving are deliberately separate operations — an abandoned parse
 * must not leave a row behind, and re-parsing to fix a bad paste must not create
 * a duplicate report.
 */
@Service
@RequiredArgsConstructor
public class CmReportService {

    private final CmReportRepository cmReportRepository;
    private final CmReportExtractionService cmReportExtractionService;
    private final UserRepository userRepository;

    /**
     * Ceiling on a stored signature image. The client downscales to ~600px before
     * upload (tens of KB), so this only catches a client that skipped the resize —
     * it is the actual boundary, not the resize helper.
     */
    private static final int MAX_SIGNATURE_CHARS = 512 * 1024;

    /** Runs the AI extraction. Persists nothing — the caller reviews the draft first. */
    public CmReportDraft parse(String sourceText) {
        return cmReportExtractionService.extractCmReport(sourceText);
    }

    @Transactional(readOnly = true)
    public List<CmReportResponse> search(String keyword) {
        List<CmReport> found = (keyword == null || keyword.isBlank())
                ? cmReportRepository.findAllByOrderByCreatedAtDesc()
                : cmReportRepository.search(keyword.strip());
        return found.stream().map(CmReportResponse::summaryFrom).toList();
    }

    @Transactional(readOnly = true)
    public CmReportResponse getById(UUID id) {
        return CmReportResponse.from(require(id));
    }

    @Transactional
    public CmReportResponse create(CmReportRequest request, UUID createdBy) {
        CmReport report = CmReport.builder()
                .reportDate(request.reportDate())
                .ticketNo(trimToNull(request.ticketNo()))
                .customerName(request.customerName().strip())
                .technicianName(trimToNull(request.technicianName()))
                .robotModel(trimToNull(request.robotModel()))
                .serialNumber(trimToNull(request.serialNumber()))
                .causeDetail(trimToNull(request.causeDetail()))
                .inspectionResult(trimToNull(request.inspectionResult()))
                .correctiveActions(trimToNull(request.correctiveActions()))
                .testResult(trimToNull(request.testResult()))
                .sourceText(trimToNull(request.sourceText()))
                .providerSignature(validateSignature(request.providerSignature(), "ลงนามผู้ให้บริการ"))
                .receiverSignature(validateSignature(request.receiverSignature(), "ลงนามผู้รับบริการ"))
                .createdBy(createdBy == null ? null : userRepository.findById(createdBy).orElse(null))
                .build();
        return CmReportResponse.from(cmReportRepository.save(report));
    }

    @Transactional
    public CmReportResponse update(UUID id, CmReportRequest request) {
        CmReport report = require(id);
        report.setReportDate(request.reportDate());
        report.setTicketNo(trimToNull(request.ticketNo()));
        report.setCustomerName(request.customerName().strip());
        report.setTechnicianName(trimToNull(request.technicianName()));
        report.setRobotModel(trimToNull(request.robotModel()));
        report.setSerialNumber(trimToNull(request.serialNumber()));
        report.setCauseDetail(trimToNull(request.causeDetail()));
        report.setInspectionResult(trimToNull(request.inspectionResult()));
        report.setCorrectiveActions(trimToNull(request.correctiveActions()));
        report.setTestResult(trimToNull(request.testResult()));
        report.setSourceText(trimToNull(request.sourceText()));
        report.setProviderSignature(validateSignature(request.providerSignature(), "ลงนามผู้ให้บริการ"));
        report.setReceiverSignature(validateSignature(request.receiverSignature(), "ลงนามผู้รับบริการ"));
        return CmReportResponse.from(cmReportRepository.save(report));
    }

    @Transactional
    public void delete(UUID id) {
        cmReportRepository.delete(require(id));
    }

    private CmReport require(UUID id) {
        return cmReportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CmReport", "id", id));
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Signatures are stored inline in the row, so an un-resized phone photo would
     * bloat the table and every subsequent read of the report. Rejects anything that
     * is not an image data URI, or that exceeds {@link #MAX_SIGNATURE_CHARS}.
     */
    private static String validateSignature(String dataUri, String label) {
        String value = trimToNull(dataUri);
        if (value == null) return null;
        if (!value.startsWith("data:image/")) {
            throw new BadRequestException(
                    "Signature for '" + label + "' must be an uploaded image.");
        }
        if (value.length() > MAX_SIGNATURE_CHARS) {
            throw new BadRequestException(
                    "Signature image for '" + label + "' is too large. Please upload a smaller photo.");
        }
        return value;
    }
}
