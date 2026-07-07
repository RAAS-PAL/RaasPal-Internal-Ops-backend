package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.report.entity.ReportSend;
import com.raaspal.robotrecommendation.report.repository.ReportSendRepository;
import com.raaspal.robotrecommendation.robotunit.dto.RobotUnitResponse;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.ReportCadence;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.service.RobotUnitService;
import com.raaspal.robotrecommendation.telemetry.core.TelemetrySyncService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final RobotUnitService robotUnitService;
    private final TelemetrySyncService telemetrySyncService;
    private final ReportEmailService reportEmailService;
    private final ReportSendRepository reportSendRepository;

    /** Summary of a whole-month delivery run. */
    public record RunSummary(String month, int sent, int skipped, int failed) {
    }

    /** Whether a run is currently executing, for which month, and the last finished summary. */
    public record RunStatus(boolean running, String month, RunSummary lastSummary) {
    }

    /*
     * A whole-month run syncs every robot from the brand API before sending, which
     * can take minutes — far longer than an HTTP client waits. So the admin
     * endpoint starts the run on this single background thread and returns
     * immediately; the UI polls status()/the report_sends history for progress.
     * The single thread + the compare-and-set flag guarantee at most one run at a
     * time (a second click while running is rejected, not queued).
     */
    private final ExecutorService runExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "report-delivery-run");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean runInProgress = new AtomicBoolean(false);
    private volatile String runningMonth;
    private volatile RunSummary lastRunSummary;

    /**
     * Starts a whole-month delivery in the background. Returns false — without
     * starting anything — if a run is already in progress.
     */
    public boolean startRunAsync(String month) {
        if (!runInProgress.compareAndSet(false, true)) {
            log.warn("Delivery run for {} rejected — a run for {} is already in progress", month, runningMonth);
            return false;
        }
        runningMonth = month;
        runExecutor.submit(() -> {
            try {
                lastRunSummary = deliverForMonth(month);
            } catch (Exception e) {
                log.error("Background delivery run for {} crashed: {}", month, e.getMessage(), e);
            } finally {
                runningMonth = null;
                runInProgress.set(false);
            }
        });
        return true;
    }

    /**
     * Starts a single-customer send in the background. Syncing one customer's
     * robots can itself take minutes (e.g. a site with 70+ robots), so it uses
     * the same background thread and in-progress guard as the whole-month run:
     * the HTTP request returns instantly (no browser timeout → no retry → no
     * duplicate emails), and only one delivery operation runs at a time. The
     * outcome lands in report_sends, which the UI history shows.
     */
    public boolean startSendAsync(UUID customerProfileId, String month) {
        if (!runInProgress.compareAndSet(false, true)) {
            log.warn("Single send for customer {} ({}) rejected — a delivery for {} is already in progress",
                    customerProfileId, month, runningMonth);
            return false;
        }
        runningMonth = month;
        runExecutor.submit(() -> {
            try {
                deliverToCustomer(customerProfileId, month);
            } catch (Exception e) {
                log.error("Background send for customer {} ({}) crashed: {}", customerProfileId, month, e.getMessage(), e);
            } finally {
                runningMonth = null;
                runInProgress.set(false);
            }
        });
        return true;
    }

    public RunStatus status() {
        return new RunStatus(runInProgress.get(), runningMonth, lastRunSummary);
    }

    @PreDestroy
    void shutdownRunExecutor() {
        runExecutor.shutdownNow();
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
            // Whole month already synced above, so don't re-sync per customer.
            ReportSend result = deliverToCustomer(customerId, month, false);
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
     * Syncs that customer's robots for the month first, so the report reflects the
     * latest telemetry without anyone clicking "Sync" manually. Always attempts to
     * send (no idempotency skip) — used by the admin "send now / resend" action.
     * Failures are recorded as FAILED, not thrown.
     */
    public ReportSend deliverToCustomer(UUID customerProfileId, String month) {
        return deliverToCustomer(customerProfileId, month, true);
    }

    /**
     * @param syncFirst sync this customer's robots before sending. False when the
     *                  caller already synced the whole month (the bulk run), to
     *                  avoid pulling the same telemetry twice.
     */
    private ReportSend deliverToCustomer(UUID customerProfileId, String month, boolean syncFirst) {
        if (syncFirst) {
            syncCustomer(customerProfileId, month);
        }
        try {
            ReportEmailService.SentEmail result = reportEmailService.sendBundle(customerProfileId, month);
            return record(customerProfileId, month, ReportSend.Status.SENT, result.recipient(), null);
        } catch (Exception e) {
            log.error("Report delivery failed for customer {} ({}): {}", customerProfileId, month, e.getMessage(), e);
            return record(customerProfileId, month, ReportSend.Status.FAILED, null, e.getMessage());
        }
    }

    /** Pulls fresh telemetry for one customer's robots for the month, robot by robot. */
    private void syncCustomer(UUID customerProfileId, String month) {
        LocalDate from;
        LocalDate to;
        try {
            YearMonth ym = YearMonth.parse(month);
            from = ym.atDay(1);
            to = ym.atEndOfMonth();
        } catch (Exception e) {
            log.error("Invalid month '{}' for customer {} sync; sending with existing data.", month, customerProfileId);
            return;
        }
        for (RobotUnitResponse robot : robotUnitService.listByCustomer(customerProfileId)) {
            try {
                telemetrySyncService.syncBySerialNumber(robot.serialNumber(), from, to);
            } catch (Exception e) {
                // One robot's sync failure must not block the others or the send.
                log.error("Telemetry sync failed for robot {} (customer {}): {}",
                        robot.serialNumber(), customerProfileId, e.getMessage());
            }
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
