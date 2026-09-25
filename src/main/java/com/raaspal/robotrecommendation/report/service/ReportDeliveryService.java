package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
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
     * Starts a whole-month delivery in the background. {@code excludedCustomerIds}
     * lets the admin hold back specific customers for this run (e.g. a site whose
     * robots aren't fully registered yet) — they're neither synced nor emailed,
     * and are left eligible for a future run. Returns false — without starting
     * anything — if a run is already in progress.
     */
    public boolean startRunAsync(String month, Set<UUID> excludedCustomerIds) {
        if (!runInProgress.compareAndSet(false, true)) {
            log.warn("Delivery run for {} rejected — a run for {} is already in progress", month, runningMonth);
            return false;
        }
        runningMonth = month;
        runExecutor.submit(() -> {
            try {
                lastRunSummary = deliverForPeriod(month, excludedCustomerIds);
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

    /** Delivers to every eligible customer, no exclusions. Used by the monthly cron scheduler. */
    public RunSummary deliverForMonth(String month) {
        return deliverForPeriod(month, Set.of());
    }

    /** A whole-month run with some customers held back — see {@link #deliverForPeriod}. */
    public RunSummary deliverForMonth(String month, Set<UUID> excludedCustomerIds) {
        return deliverForPeriod(month, excludedCustomerIds);
    }

    /**
     * Delivers the weekly bundle for an ISO week ("YYYY-Www") to every customer with an
     * active WEEKLY-cadence robot. Used by the Monday cron scheduler. A malformed week
     * is rejected before anything is sent.
     */
    public RunSummary deliverForWeek(String week) {
        return deliverForPeriod(ReportPeriod.ofWeek(week).key(), Set.of());
    }

    /**
     * Delivers the period's bundle — a month ("YYYY-MM") or an ISO week ("YYYY-Www") —
     * to every customer with an active robot on the matching cadence (MONTHLY for a
     * month, WEEKLY for a week), except those in {@code excludedCustomerIds}
     * — held back entirely for this run (not synced, not emailed, not recorded),
     * so they remain eligible for a later run once ready. Each customer's robots
     * are synced right before sending them (not excluded/already-sent customers'
     * robots are never touched, saving time when a large site is held back).
     * Idempotent: customers already SENT for the period are skipped, so a re-run —
     * or the scheduler firing after someone already ran it by hand — never
     * double-sends. Never throws for a single customer — failures are
     * logged/recorded and the run continues.
     */
    public RunSummary deliverForPeriod(String month, Set<UUID> excludedCustomerIds) {
        log.info("Report delivery run starting for {}{}", month,
                excludedCustomerIds.isEmpty() ? "" : " (excluding " + excludedCustomerIds.size() + " customer(s))");

        int sent = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID customerId : eligibleCustomerIds(ReportPeriod.parse(month))) {
            if (excludedCustomerIds.contains(customerId) || isAlreadySent(customerId, month)) {
                skipped++;
                continue;
            }
            ReportSend result = deliverToCustomer(customerId, month, true);
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
            return record(customerProfileId, month, ReportSend.Kind.BUNDLE, null, ReportSend.Status.SENT, result.recipient(), null);
        } catch (Exception e) {
            log.error("Report delivery failed for customer {} ({}): {}", customerProfileId, month, e.getMessage(), e);
            return record(customerProfileId, month, ReportSend.Kind.BUNDLE, null, ReportSend.Status.FAILED, null, e.getMessage());
        }
    }

    /**
     * Sends one robot's report — the Report preview tab's Send — and records it as a
     * ROBOT_REPORT, so it appears in Delivery history alongside the bundles. It is
     * not the month's deliverable and never satisfies {@link #isAlreadySent}.
     *
     * <p>The customer is resolved here, before the send, so a failure inside the send
     * can be recorded against them; the email service repeats the lookup, but it is
     * one read. A robot that is unknown or not deployed is refused before anything is
     * attempted and leaves no row — there was no send to record. Unlike the bundle
     * path this rethrows: the person pressing Send is waiting for the answer.
     */
    public ReportEmailService.SentEmail sendRobotReport(String serialNumber, ReportPeriod period) {
        RobotUnitResponse robot = robotUnitService.getBySerialNumber(serialNumber);
        if (robot.deployment() == null) {
            throw new BadRequestException("Robot " + serialNumber + " is not deployed to a customer.");
        }
        UUID customerId = robot.deployment().customerProfileId();
        // Recorded under the period's own key — "2026-W38" for a week — so a weekly send
        // can never be mistaken for the month's deliverable.
        String key = period.key();
        try {
            ReportEmailService.SentEmail sent = reportEmailService.send(serialNumber, period);
            record(customerId, key, ReportSend.Kind.ROBOT_REPORT, serialNumber, ReportSend.Status.SENT, sent.recipient(), null);
            return sent;
        } catch (RuntimeException e) {
            log.error("Robot report email failed for {} ({}): {}", serialNumber, key, e.getMessage(), e);
            record(customerId, key, ReportSend.Kind.ROBOT_REPORT, serialNumber, ReportSend.Status.FAILED, null, e.getMessage());
            throw e;
        }
    }

    /** Pulls fresh telemetry for one customer's robots for the period, robot by robot. */
    private void syncCustomer(UUID customerProfileId, String month) {
        ReportPeriod period = ReportPeriod.parse(month);
        LocalDate from = period.startDate();
        LocalDate to = period.endDate();
        if (from == null || to == null) {
            log.error("Invalid period '{}' for customer {} sync; sending with existing data.", month, customerProfileId);
            return;
        }
        for (RobotUnitResponse robot : robotUnitService.listByCustomer(customerProfileId)) {
            // Delivery robots have no telemetry adapter; syncing them only logs an error.
            if (robot.robotType() == RobotType.DELIVERY) continue;
            try {
                telemetrySyncService.syncBySerialNumber(robot.serialNumber(), from, to);
            } catch (Exception e) {
                // One robot's sync failure must not block the others or the send.
                log.error("Telemetry sync failed for robot {} (customer {}): {}",
                        robot.serialNumber(), customerProfileId, e.getMessage());
            }
        }
    }

    /** Delivery history for a period (month or ISO week key), newest first. */
    public List<ReportSend> historyForMonth(String month) {
        return reportSendRepository.findByReportMonthOrderBySentAtDesc(month);
    }

    /**
     * Customers with at least one active cleaning robot on the cadence this period
     * serves: MONTHLY for a month, WEEKLY for a week. The cadence setting is one value
     * per robot, so a robot set to WEEKLY leaves the monthly run and joins the weekly one.
     */
    private Set<UUID> eligibleCustomerIds(ReportPeriod period) {
        ReportCadence cadence = period.type() == ReportPeriod.Type.WEEK ? ReportCadence.WEEKLY : ReportCadence.MONTHLY;
        Set<UUID> customerIds = new LinkedHashSet<>();
        for (Deployment deployment : deploymentRepository.findByIsActiveTrue()) {
            // A customer whose only robots are delivery robots has nothing in the cleaning
            // bundle; making them eligible would send an empty email.
            if (deployment.getRobotUnit().getRobotType() == RobotType.DELIVERY) continue;
            if (deployment.getReportCadence() == cadence) {
                customerIds.add(deployment.getCustomerProfile().getId());
            }
        }
        log.info("{} customer(s) eligible for the {} report", customerIds.size(), cadence);
        return customerIds;
    }

    /** A SENT bundle for the month. A robot report sent by hand does not count. */
    private boolean isAlreadySent(UUID customerProfileId, String month) {
        return reportSendRepository
                .findByCustomerProfileIdAndReportMonthAndKindAndStatus(
                        customerProfileId, month, ReportSend.Kind.BUNDLE, ReportSend.Status.SENT)
                .isPresent();
    }

    /** The one place a history row is written; every send, of every kind, passes through here. */
    private ReportSend record(UUID customerId, String month, ReportSend.Kind kind, String robotSerial,
                              ReportSend.Status status, String recipient, String error) {
        return reportSendRepository.save(ReportSend.builder()
                .customerProfileId(customerId)
                .reportMonth(month)
                .kind(kind)
                .robotSerial(robotSerial)
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
