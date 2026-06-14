package com.raaspal.robotrecommendation.report.scheduler;

import com.raaspal.robotrecommendation.report.service.MonthlyReportService;
import com.raaspal.robotrecommendation.report.service.MonthlyReportSummary;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Optional monthly cron that generates and delivers the previous month's
 * reports. Disabled unless {@code app.reports.scheduler-enabled=true} — the
 * manual {@code POST /api/v1/reports/monthly/run} endpoint covers everything
 * otherwise, and leaving the scheduler off avoids accidental live sends until
 * the n8n flow + LINE Official Account are confirmed.
 */
@Component
@ConditionalOnProperty(name = "app.reports.scheduler-enabled", havingValue = "true")
@RequiredArgsConstructor
public class MonthlyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReportScheduler.class);

    private final MonthlyReportService monthlyReportService;

    @Scheduled(cron = "${app.reports.scheduler-cron}")
    public void sendPreviousMonth() {
        String month = YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString();
        try {
            MonthlyReportSummary summary = monthlyReportService.generateAndSend(month, false);
            log.info("Scheduled monthly report {} sent: {} customer(s), {} message(s)",
                    month, summary.customersProcessed(), summary.messagesSent());
        } catch (RuntimeException e) {
            log.error("Scheduled monthly report {} failed: {}", month, e.getMessage(), e);
        }
    }
}
