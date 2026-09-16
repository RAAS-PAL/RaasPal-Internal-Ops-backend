package com.raaspal.robotrecommendation.pm.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The 52-week grid: one row per contract, one cell per ISO week. */
public record PmYearResponse(int year, int weekCount, List<Row> rows, List<WeekTotal> weekTotals,
                             PmSummary summary) {

    /**
     * One contract's year.
     *
     * <p>{@code cells} is keyed by ISO week number and holds only the weeks that
     * have visits, which is nearly always a handful out of 52 - sending 52 mostly
     * empty cells per row would multiply the payload for nothing.
     */
    public record Row(UUID contractId, String name, String customerName, String project, String serviceLine,
                      String province, String region, String zone, String robotModel, Integer robotCount,
                      long totalVisits, Map<Integer, Cell> cells) {
    }

    /** The visits of one contract in one week. */
    public record Cell(int week, long total, Map<String, Long> byStatus, String dominantStatus) {
    }

    /** Column footer: the whole organisation's load in one week. */
    public record WeekTotal(int week, long total, Map<String, Long> byStatus) {
    }
}
