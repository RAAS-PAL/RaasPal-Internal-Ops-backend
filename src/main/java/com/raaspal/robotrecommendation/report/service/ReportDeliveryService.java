package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.report.entity.ReportSend;
import com.raaspal.robotrecommendation.report.repository.ReportSendRepository;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.ReportCadence;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates sending customers their monthly report bundle and recording the
 * outcome in {@code report_sends}. Shared by the automated scheduler and the
 * admin "send now" endpoint, so idempotency and history behave identically
 * whether a run is triggered on a cron or by hand.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportDeliveryService {

    private final DeploymentRepository deploymentRepository;
    private final TelemetrySyncService telemetrySyncService;
    private final ReportEmailService reportEmailService;
    private final ReportSendRepository reportSendRepository;

    /** Summary of a whole-month delivery run. */
    public record RunSummary(String month, int sent, int skipped, int failed) {
    }

    /**
     * Delivers the monthly bundle to every customer with an active MONTHLY-cadence
     * robot for {@code month} ("YYYY-MM"). Syncs telemetry for the month first so
     * figures are complete. Idempotent: customers already SENT for the month are
     * skipped. Never throws for a single customer — failures are logged/recorded
     * and the run continues.
     */
    public RunSummary deliverForMonth(String month) {
        log.info("Report delivery run starting for month {}", month);
        syncMonth(month);

        int sent = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID customerId : eligibleCustomerIds()) {
            if (isAlreadySent(customerId, month)) {
                skipped++;
                continue;
            }
            ReportSend result = deliverToCustomer(customerId, month);
            switch (result.getStatus()) {
                case SENT -> sent++;
                case FAILED -> failed++;
                default -> skipped++;
            }
        }

        log.info("Report delivery run for {} complete: {} sent, {} skipped, {} failed", month, sent, skipped, failed);
        return new RunSummary(month, sent, skipped, failed);
    }

    /**
     * Sends one customer their bundle for {@code month} and records the result.
     * Always attempts to send (no idempotency skip) — used by the admin
     * "send now / resend" action. Failures are recorded as FAILED, not thrown.
     */
    public ReportSend deliverToCustomer(UUID customerProfileId, String month) {
        try {
            ReportEmailService.SentEmail result = reportEmailService.sendBundle(customerProfileId, month);
            return record(customerProfileId, month, ReportSend.Status.SENT, result.recipient(), null);
        } catch (Exception e) {
            log.error("Report delivery failed for customer {} ({}): {}", customerProfileId, month, e.getMessage(), e);
            return record(customerProfileId, month, ReportSend.Status.FAILED, null, e.getMessage());
        }
    }

    /** Delivery history for a month, newest first. */
    public List<ReportSend> historyForMonth(String month) {
        return reportSendRepository.findByReportMonthOrderBySentAtDesc(month);
    }

    private void syncMonth(String month) {
        try {
            YearMonth ym = YearMonth.parse(month);
            telemetrySyncService.syncRange(ym.atDay(1), ym.atEndOfMonth());
        } catch (Exception e) {
            log.error("Telemetry sync failed for {}; sending reports with existing data. {}", month, e.getMessage(), e);
        }
    }

    private Set<UUID> eligibleCustomerIds() {
        Set<UUID> customerIds = new LinkedHashSet<>();
        for (Deployment deployment : deploymentRepository.findByIsActiveTrue()) {
            if (deployment.getReportCadence() == ReportCadence.MONTHLY) {
                customerIds.add(deployment.getCustomerProfile().getId());
            }
        }
        log.info("{} customer(s) eligible for monthly report", customerIds.size());
        return customerIds;
    }

    private boolean isAlreadySent(UUID customerProfileId, String month) {
        return reportSendRepository
                .findByCustomerProfileIdAndReportMonthAndStatus(customerProfileId, month, ReportSend.Status.SENT)
                .isPresent();
    }

    private ReportSend record(UUID customerId, String month, ReportSend.Status status, String recipient, String error) {
        return reportSendRepository.save(ReportSend.builder()
                .customerProfileId(customerId)
                .reportMonth(month)
                .status(status)
                .recipientEmail(recipient)
                .errorMessage(truncate(error))
                .sentAt(LocalDateTime.now())
                .build());
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
