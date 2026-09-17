package com.raaspal.robotrecommendation.casereport.brand.dto;

import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything the analytics page draws, in one response.
 *
 * <p>Counts are over the tickets whose date falls in {@code from..to}; the two
 * exceptions are named for it: {@link Kpis#openNow} and {@link Kpis#oldestOpenDays}
 * are about the board today, whatever the range.
 */
@Builder
public record BrandTicketSummary(
        String brand,
        String label,
        String boardId,
        LocalDate from,
        LocalDate to,
        LocalDateTime lastSyncedAt,
        Totals totals,
        Kpis kpis,
        List<MonthPoint> monthly,
        List<Count> statuses,
        List<Count> rootCauses,
        List<Count> models,
        List<Count> reOwners,
        List<Count> aging,
        List<SiteCount> topSites,
        List<RobotCount> repeatRobots,
        Definitions definitions
) {

    @Builder
    public record Totals(int tickets, int open, int done, int robots, int sites) {
    }

    @Builder
    public record Kpis(
            int openNow,
            Integer oldestOpenDays,
            int thisMonth,
            int lastMonth,
            /** this month over last month, in percent; null when last month had none. */
            Double monthDeltaPct,
            /** Open Date to RE Action, median over tickets with both. */
            Double medianDaysToAction,
            int actionSample,
            /** Share of tickets whose RE Action fell within 7 days of Open Date. */
            Double slaWithin7Pct,
            /** Share of tickets followed by another on the same serial within 14 days. */
            Double repeatRatePct,
            int repeatSample
    ) {
    }

    /** One month: how many were opened, and how many of those are still open today. */
    public record MonthPoint(String month, int opened, int open, int done) {
    }

    public record Count(String label, int count) {
    }

    public record SiteCount(String label, int count, int open) {
    }

    public record RobotCount(String serial, String model, String site, int count, LocalDate lastOpenDate) {
    }

    /** The rules behind the KPIs, shown as tooltips so nobody has to guess them. */
    public record Definitions(String open, String slaDays, String repeatDays, String ticketDate) {
    }
}
