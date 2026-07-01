package com.raaspal.robotrecommendation.report.scheduler;

import com.raaspal.robotrecommendation.report.service.ReportDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Fires the automated monthly report delivery on a cron (see
 * {@code app.reports.email-scheduler-cron}), interpreted in
 * {@code app.reports.email-scheduler-zone} (default Asia/Bangkok), sending the
 * <em>previous</em> month's bundle to each customer. The actual work lives in
 * {@link ReportDeliveryService} so it can also be run manually and unit-tested.
 *
 * <p>Disabled by default — the bean only registers when
 * {@code app.reports.email-scheduler-enabled=true} (set once SMTP is configured).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.reports.email-scheduler-enabled", havingValue = "true")
@RequiredArgsConstructor
public class ReportDeliveryScheduler {

    private final ReportDeliveryService reportDeliveryService;

    @Value("${app.reports.email-scheduler-zone}")
    private String schedulerZone;

    @Scheduled(cron = "${app.reports.email-scheduler-cron}", zone = "${app.reports.email-scheduler-zone}")
    public void run() {
        // Compute "previous month" in the same zone the cron fires in, so the
        // month boundary matches the local schedule (not the server's UTC clock).
        String previousMonth = YearMonth.now(ZoneId.of(schedulerZone)).minusMonths(1).toString();
        log.info("Scheduled report delivery triggered for {}", previousMonth);
        reportDeliveryService.deliverForMonth(previousMonth);
    }
}
