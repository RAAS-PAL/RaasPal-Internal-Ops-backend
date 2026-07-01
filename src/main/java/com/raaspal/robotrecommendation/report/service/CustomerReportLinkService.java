package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.report.dto.CustomerReportBundleResponse;
import com.raaspal.robotrecommendation.report.entity.CustomerReportLink;
import com.raaspal.robotrecommendation.report.repository.CustomerReportLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Mints and resolves public customer report-bundle links. One stable token
 * per customer+month, so re-sharing the same month returns the same URL (the
 * monthly email reuses it).
 */
@Service
@RequiredArgsConstructor
public class CustomerReportLinkService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CustomerReportLinkRepository customerReportLinkRepository;
    private final CustomerReportBundleService customerReportBundleService;

    /** Returns the shareable token for a customer+month, creating it on first use. */
    @Transactional
    public String createOrGetToken(UUID customerProfileId, String month) {
        return customerReportLinkRepository.findByCustomerProfileIdAndReportMonth(customerProfileId, month)
                .map(CustomerReportLink::getToken)
                .orElseGet(() -> customerReportLinkRepository.save(CustomerReportLink.builder()
                        .token(generateToken())
                        .customerProfileId(customerProfileId)
                        .reportMonth(month)
                        .build()).getToken());
    }

    /** Resolves a public token to its customer's report bundle. 404 if unknown. */
    @Transactional(readOnly = true)
    public CustomerReportBundleResponse resolve(String token) {
        CustomerReportLink link = customerReportLinkRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("CustomerReportLink", "token", token));
        return customerReportBundleService.build(link.getCustomerProfileId(), link.getReportMonth());
    }

    private String generateToken() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
