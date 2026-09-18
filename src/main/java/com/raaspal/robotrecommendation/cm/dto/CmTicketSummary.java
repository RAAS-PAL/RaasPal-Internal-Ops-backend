package com.raaspal.robotrecommendation.cm.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One monday ticket as a row the staff pick a CM report from.
 *
 * @param caseId       the monday item id - what the report calls "Ticket No."
 * @param board        CLEANING or DELIVERY
 * @param commentCount how much the technicians have written; a ticket with none
 *                     will extract little beyond its columns
 * @param hasReport    a CM report with this ticket number already exists
 */
public record CmTicketSummary(
        UUID caseTicketId,
        String caseId,
        CmTicketBoard board,
        String itemName,
        String project,
        String branch,
        String province,
        String robotModel,
        String serialNumbers,
        String status,
        String supStatus,
        String mainIssue,
        LocalDate openDate,
        int commentCount,
        LocalDateTime lastCommentAt,
        boolean hasReport) {

    public enum CmTicketBoard { CLEANING, DELIVERY }
}
