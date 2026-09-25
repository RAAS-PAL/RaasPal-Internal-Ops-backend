package com.raaspal.robotrecommendation.report.scheduler;

import com.raaspal.robotrecommendation.report.service.ReportDeliveryService;
import com.raaspal.robotrecommendation.report.service.ReportPeriod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Fires the automated weekly report delivery on a cron (see
 * {@code app.reports.weekly-scheduler-cron}, default every Monday 08:00), sending the
 * week that has just ended — Monday to Sunday — to every customer with a robot set to
 * {@code WEEKLY}. The work lives in {@link ReportDeliveryService}, shared with the
 * monthly run: same sync-then-send per customer, same history, and the same
 * "already sent for this period" skip, so a manual run earlier that Monday is never
 * repeated.
 *
 * <p>Its own switch, {@code app.reports.weekly-scheduler-enabled}, separate from the
 * monthly one: weekly can be turned on for one customer without the monthly
 * automation having to be live, and either can be turned off without the other.
 * Disabled by default.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.reports.weekly-scheduler-enabled", havingValue = "true")
@RequiredArgsConstructor
public class WeeklyReportDeliveryScheduler {

    private final ReportDeliveryService reportDeliveryService;

    @Value("${app.reports.email-scheduler-zone}")
    private String schedulerZone;

    @Scheduled(cron = "${app.reports.weekly-scheduler-cron}", zone = "${app.reports.email-scheduler-zone}")
    public void run() {
        String week = previousWeek(LocalDate.now(ZoneId.of(schedulerZone)));
        log.info("Scheduled weekly report delivery triggered for {}", week);
        reportDeliveryService.deliverForWeek(week);
    }

    /**
     * The ISO week before the one containing {@code today}. On the Monday the cron
     * fires, that is the Monday-to-Sunday week that ended yesterday. Computed in the
     * cron's own zone, so the week boundary is Bangkok midnight, not the server's UTC.
     */
    static String previousWeek(LocalDate today) {
        return ReportPeriod.weekContaining(today.minusWeeks(1)).key();
    }
}
