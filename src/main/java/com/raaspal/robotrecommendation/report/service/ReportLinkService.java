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
 * Mints and resolves public report links. One stable token per robot+period, so
 * re-sharing the same month or week returns the same URL (the report email reuses it).
 */
@Service
@RequiredArgsConstructor
public class ReportLinkService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReportLinkRepository reportLinkRepository;
    private final ReportCacheService reportCacheService;

    /**
     * Returns the shareable token for a robot and period (a month or an ISO week),
     * creating it on first use. Taking a {@link ReportPeriod} rather than a raw string
     * means the key has already been validated — a malformed week never becomes a
     * stored link that resolves to nothing.
     */
    @Transactional
    public String createOrGetToken(String serialNumber, ReportPeriod period) {
        String key = period.key();
        return reportLinkRepository.findBySerialNumberAndReportMonth(serialNumber, key)
                .map(ReportLink::getToken)
                .orElseGet(() -> reportLinkRepository.save(ReportLink.builder()
                        .token(generateToken())
                        .serialNumber(serialNumber)
                        .reportMonth(key)
                        .build()).getToken());
    }

    /** Resolves a public token to its aggregated report (served from cache). 404 if unknown. */
    public ReportPreviewResponse resolve(String token) {
        ReportLink link = reportLinkRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("ReportLink", "token", token));
        return reportCacheService.getRobotReport(link.getSerialNumber(), link.getReportMonth());
    }

    private String generateToken() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
