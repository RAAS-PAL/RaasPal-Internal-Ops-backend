package com.raaspal.robotrecommendation.robotunit.service;

import com.raaspal.robotrecommendation.robotunit.dto.ContractDocumentInfo;
import com.raaspal.robotrecommendation.robotunit.dto.ContractExpiryResponse;
import com.raaspal.robotrecommendation.robotunit.dto.ContractRenewalFollowup;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which robots' contracts are ending, and which have ended.
 *
 * <p>Two audiences. The console shows the list so a customer's renewal is visible a
 * quarter out rather than discovered when the robot stops reporting; the daily alert
 * ({@code OpsAlertScheduler}) emails each deployment once as it crosses into the
 * window, stamping {@code contractExpiryAlertedAt} so a day the scheduler did not run
 * is caught up rather than missed. Changing the end date clears the stamp, so an
 * extended contract is alerted again as its new end approaches.
 *
 * <p>Only active deployments with an end date take part. A null end date is an open
 * contract and is never "ending".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractExpiryService {

    /**
     * The alert window: a contract ending within this many days is "ending soon".
     * 90, not 30 - the CS team needs a quarter to call, quote and get a renewal signed
     * (raised from 30 on 2026-09-18). The email scheduler reads its own copy from
     * {@code app.alerts.contract-window-days}; keep the two the same.
     */
    public static final int DEFAULT_WINDOW_DAYS = 90;

    private final DeploymentRepository deploymentRepository;

    @Value("${app.reports.business-zone:Asia/Bangkok}")
    private String businessZone;

    /** Ending within {@code withinDays} (today inclusive), and already ended, in that order. */
    @Transactional(readOnly = true)
    public ContractExpiryResponse list(int withinDays) {
        LocalDate today = today();
        LocalDate horizon = today.plusDays(withinDays);

        List<ContractExpiryResponse.Contract> endingSoon = new ArrayList<>();
        List<ContractExpiryResponse.Contract> ended = new ArrayList<>();
        for (Deployment d : deploymentRepository.findActiveWithEndDateOnOrBefore(horizon)) {
            ContractExpiryResponse.Contract c = toContract(d, today);
            if (d.getContractEndDate().isBefore(today)) {
                ended.add(c);
            } else {
                endingSoon.add(c);
            }
        }
        endingSoon.sort(Comparator.comparing(ContractExpiryResponse.Contract::contractEndDate));
        // Most recently ended first: the ones somebody may still be able to do something about.
        ended.sort(Comparator.comparing(ContractExpiryResponse.Contract::contractEndDate).reversed());

        return new ContractExpiryResponse(today, withinDays, endingSoon, ended);
    }

    /**
     * Every active deployment, soonest end date first; those with no end date last.
     * The Contracts page's "All" view — the same rows the ending-soon list is cut
     * from, so a contract can have its PDF attached long before it is nearly over.
     */
    @Transactional(readOnly = true)
    public ContractExpiryResponse.All all(int withinDays) {
        LocalDate today = today();
        List<ContractExpiryResponse.Contract> rows = new ArrayList<>();
        for (Deployment d : deploymentRepository.findActiveWithRobotAndCustomer()) {
            rows.add(toContract(d, today, withinDays));
        }
        rows.sort(Comparator.comparing(ContractExpiryResponse.Contract::contractEndDate,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ContractExpiryResponse.Contract::customerName, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(ContractExpiryResponse.Contract::serialNumber, Comparator.nullsLast(String::compareTo)));
        return new ContractExpiryResponse.All(today, withinDays, rows);
    }

    /**
     * The deployments to alert today: ending within the window and not yet alerted.
     * The caller sends the alert and then calls {@link #markAlerted}.
     */
    @Transactional(readOnly = true)
    public List<Deployment> dueForAlert(int withinDays) {
        LocalDate today = today();
        return deploymentRepository.findActiveEndingBetweenNotAlerted(today, today.plusDays(withinDays));
    }

    @Transactional
    public void markAlerted(List<Deployment> deployments) {
        Instant now = Instant.now();
        for (Deployment d : deployments) {
            d.setContractExpiryAlertedAt(now);
        }
        deploymentRepository.saveAll(deployments);
    }

    /** Where the deployment's contract stands relative to today, for the robot list. */
    public ContractExpiryResponse.Status statusOf(LocalDate end) {
        return statusOf(end, today(), DEFAULT_WINDOW_DAYS);
    }

    public static ContractExpiryResponse.Status statusOf(LocalDate end, LocalDate today, int windowDays) {
        if (end == null) return ContractExpiryResponse.Status.NONE;
        if (end.isBefore(today)) return ContractExpiryResponse.Status.ENDED;
        if (!end.isAfter(today.plusDays(windowDays))) return ContractExpiryResponse.Status.ENDING_SOON;
        return ContractExpiryResponse.Status.ACTIVE;
    }

    public ContractExpiryResponse.Contract toContract(Deployment d, LocalDate today) {
        return toContract(d, today, DEFAULT_WINDOW_DAYS);
    }

    public ContractExpiryResponse.Contract toContract(Deployment d, LocalDate today, int windowDays) {
        LocalDate end = d.getContractEndDate();
        return new ContractExpiryResponse.Contract(
                d.getRobotUnit().getId(),
                d.getRobotUnit().getSerialNumber(),
                d.getRobotUnit().getName(),
                d.getRobotUnit().getBrand(),
                d.getRobotUnit().getModel(),
                d.getCustomerProfile().getId(),
                d.getCustomerProfile().getCompanyName(),
                d.getSite(),
                d.getContractStartDate(),
                end,
                end == null ? null : ChronoUnit.DAYS.between(today, end),
                statusOf(end, today, windowDays),
                d.getContractExpiryAlertedAt(),
                d.getContractDocument() == null ? null : ContractDocumentInfo.of(
                        d.getContractDocument(),
                        deploymentRepository.countByContractDocumentId(d.getContractDocument().getId())),
                ContractRenewalFollowup.of(d));
    }

    LocalDate today() {
        return LocalDate.now(ZoneId.of(businessZone));
    }
}
