package com.raaspal.robotrecommendation.pm.dto;

/**
 * The tile row above both views.
 *
 * <p>{@code undatedBacklog} is the number the source data made necessary: a large
 * minority of visits carry no plan date at all, so they appear in no week and no
 * month. Counting them here is the difference between a planner that says
 * "nothing due" and one that says "nothing scheduled, and here is how much".
 */
public record PmSummary(long totalVisits, long customers, long robots, long planned, long inProgress,
                        long completed, long unplanned, long overdue, long undatedBacklog) {
}
