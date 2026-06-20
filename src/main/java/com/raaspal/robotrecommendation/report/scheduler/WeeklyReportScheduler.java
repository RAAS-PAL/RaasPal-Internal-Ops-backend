package com.raaspal.robotrecommendation.report.scheduler;

import com.raaspal.robotrecommendation.report.service.MonthlyReportService;
import com.raaspal.robotrecommendation.report.service.MonthlyReportSummary;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional weekly cron that generates and delivers the previous full week's
 * reports. Disabled unless {@code app.reports.weekly-scheduler-enabled=true} —
 * the manual {@code POST /api/v1/reports/weekly/run} endpoint covers everything
 * otherwise, and leaving the scheduler off avoids accidental live sends until
 * the n8n flow + LINE Official Account are confirmed.
 *
 * <p>Kept separate from {@link MonthlyReportScheduler} so each cadence can be
 * toggled independently.
 */
@Component
@ConditionalOnProperty(name = "app.reports.weekly-scheduler-enabled", havingValue = "true")
@RequiredArgsConstructor
public class WeeklyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);

    private final MonthlyReportService monthlyReportService;

    @Scheduled(cron = "${app.reports.weekly-scheduler-cron}")
    public void sendPreviousWeek() {
        try {
            // null weekStart → the service resolves the previous full ISO week.
            MonthlyReportSummary summary = monthlyReportService.generateAndSendWeekly(null, false);
            log.info("Scheduled weekly report {} sent: {} customer(s), {} message(s)",
                    summary.reportMonth(), summary.customersProcessed(), summary.messagesSent());
        } catch (RuntimeException e) {
            log.error("Scheduled weekly report failed: {}", e.getMessage(), e);
        }
    }
}
