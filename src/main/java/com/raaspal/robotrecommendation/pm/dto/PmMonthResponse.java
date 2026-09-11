package com.raaspal.robotrecommendation.pm.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The month view: every visit in a date range, plus its tiles. */
public record PmMonthResponse(LocalDate from, LocalDate to, PmSummary summary, List<Row> rows) {

    /**
     * One visit.
     *
     * <p>{@code daysOverdue} is positive when the plan date has passed and the visit
     * is not complete, null otherwise. Computed per request rather than stored,
     * because it changes every midnight.
     */
    public record Row(UUID visitId, String visitName, Integer pmSequence, LocalDate planDate, LocalDate actionDate,
                      String timeText, String statusRaw, String statusBucket, Integer daysOverdue,
                      String ownerNames, UUID contractId, String contractName, String customerName, String project,
                      String serviceLine, String province, String region, String zone, String robotModel,
                      Integer robotCount, String contractType) {
    }
}
