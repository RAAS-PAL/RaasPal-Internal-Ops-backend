package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.report.dto.ReportPreviewResponse;
import com.raaspal.robotrecommendation.report.entity.ReportLink;
import com.raaspal.robotrecommendation.report.repository.ReportLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mints and resolves public report links. One stable token per robot+month, so
 * re-sharing the same month returns the same URL (the monthly email reuses it).
 */
@Service
@RequiredArgsConstructor
public class ReportLinkService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReportLinkRepository reportLinkRepository;
    private final ReportPreviewService reportPreviewService;

    /** Returns the shareable token for a robot+month, creating it on first use. */
    @Transactional
    public String createOrGetToken(String serialNumber, String month) {
        return reportLinkRepository.findBySerialNumberAndReportMonth(serialNumber, month)
                .map(ReportLink::getToken)
                .orElseGet(() -> reportLinkRepository.save(ReportLink.builder()
                        .token(generateToken())
                        .serialNumber(serialNumber)
                        .reportMonth(month)
                        .build()).getToken());
    }

    /** Resolves a public token to its aggregated report. 404 if unknown. */
    @Transactional(readOnly = true)
    public ReportPreviewResponse resolve(String token) {
        ReportLink link = reportLinkRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("ReportLink", "token", token));
        return reportPreviewService.build(link.getSerialNumber(), link.getReportMonth());
    }

    private String generateToken() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
