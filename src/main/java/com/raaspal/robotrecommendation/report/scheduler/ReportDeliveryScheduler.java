package com.raaspal.robotrecommendation.report.scheduler;

import com.raaspal.robotrecommendation.report.service.ReportDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * Fires the automated monthly report delivery on a cron (see
 * {@code app.reports.email-scheduler-cron}), sending the <em>previous</em>
 * month's bundle to each customer. The actual work lives in
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

    @Scheduled(cron = "${app.reports.email-scheduler-cron}")
    public void run() {
        String previousMonth = YearMonth.now().minusMonths(1).toString();
        log.info("Scheduled report delivery triggered for {}", previousMonth);
        reportDeliveryService.deliverForMonth(previousMonth);
    }
}
