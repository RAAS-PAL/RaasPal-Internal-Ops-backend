package com.raaspal.robotrecommendation.alerts;

import com.raaspal.robotrecommendation.robotunit.dto.ContractExpiryResponse;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.service.ContractExpiryService;
import com.raaspal.robotrecommendation.telemetry.core.ZeroDataRobotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The customer success team's two nudges, once a day in the morning.
 *
 * <p><strong>Contracts ending soon.</strong> Every deployment whose contract end falls
 * within the window and has not been alerted yet goes out in one email, then is
 * stamped. Selecting on "not yet alerted" means a morning the server was down is caught
 * up the next one. A changed end date clears the stamp (see {@code RobotUnitService}),
 * so an extension is alerted again as its new end approaches.
 *
 * <p><strong>Robots with no data.</strong> On the configured day of the month (default
 * the 3rd — the nightly sync's 3-day look-back has settled last month by then), last
 * month's worklist is emailed. Every other morning this part does nothing.
 *
 * <p>Off unless {@code app.alerts.enabled=true} and {@code app.alerts.cs-email} is set.
 * Never throws: the scheduler must be alive tomorrow whatever happened today.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.alerts.enabled", havingValue = "true")
@RequiredArgsConstructor
public class OpsAlertScheduler {

    private final ContractExpiryService contractExpiryService;
    private final ZeroDataRobotService zeroDataRobotService;
    private final OpsAlertEmailService emails;

    @Value("${app.alerts.zone:Asia/Bangkok}")
    private String zone;

    @Value("${app.alerts.contract-window-days:30}")
    private int contractWindowDays;

    @Value("${app.alerts.zero-data-day-of-month:3}")
    private int zeroDataDayOfMonth;

    @Scheduled(cron = "${app.alerts.cron:0 0 8 * * *}", zone = "${app.alerts.zone:Asia/Bangkok}")
    public void morning() {
        if (!emails.isConfigured()) {
            log.info("Ops alerts: app.alerts.cs-email is not set; nothing sent");
            return;
        }
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        try {
            alertContractsEndingSoon(today);
        } catch (Exception e) {
            log.error("Contract expiry alert failed: {}", e.getMessage(), e);
        }
        try {
            if (today.getDayOfMonth() == zeroDataDayOfMonth) {
                sendZeroDataDigest(YearMonth.from(today).minusMonths(1).toString());
            }
        } catch (Exception e) {
            log.error("Zero-data digest failed: {}", e.getMessage(), e);
        }
    }

    /** Also callable by hand; returns how many contracts were alerted. */
    public int alertContractsEndingSoon(LocalDate today) {
        List<Deployment> due = contractExpiryService.dueForAlert(contractWindowDays);
        if (due.isEmpty()) {
            log.info("Contract expiry alert: nothing new within {} days", contractWindowDays);
            return 0;
        }
        List<ContractExpiryResponse.Contract> contracts = new ArrayList<>();
        for (Deployment d : due) {
            contracts.add(contractExpiryService.toContract(d, today));
        }
        if (emails.sendContractExpiryAlert(contracts, contractWindowDays)) {
            contractExpiryService.markAlerted(due);
            log.info("Contract expiry alert: {} contract(s) alerted", due.size());
            return due.size();
        }
        return 0;
    }

    public boolean sendZeroDataDigest(String month) {
        boolean sent = emails.sendZeroDataDigest(zeroDataRobotService.forMonth(month));
        log.info("Zero-data digest for {}: {}", month, sent ? "sent" : "not sent");
        return sent;
    }
}
