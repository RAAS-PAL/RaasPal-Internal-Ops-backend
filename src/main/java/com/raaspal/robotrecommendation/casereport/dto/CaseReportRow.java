package com.raaspal.robotrecommendation.casereport.dto;

import com.raaspal.robotrecommendation.casereport.service.SlaStatus;

import java.time.LocalDate;

/**
 * One printed line of a pending-case report.
 *
 * <p>The field order is the column order of the {@code Raw_Delivery} sheet, so the Excel
 * writer and the review table walk the same list and cannot disagree about layout.
 *
 * <p>Two fields are carried that the sheet does not print. {@link #province} explains a
 * blank SLA — without it a reviewer sees an empty cell and no reason for it. And
 * {@link #sourceItemId} is what a reviewer clicks to open the ticket on monday, which is
 * where a wrong value actually gets fixed.
 */
public record CaseReportRow(

        /** 1-based, assigned at render time. Not an id: it renumbers as rows come and go. */
        int no,

        /** Customer, with the board's {@code #} prefix stripped — "#MK" reads as "MK". */
        String project,

        /**
         * Branch code and name joined, as the sheet's "Brucn" column shows them:
         * "M154 โลตัส จันทบุรี". The code is absent on roughly half the tickets, and the
         * name then stands alone rather than leaving a leading space.
         */
        String branch,

        String robot,
        String serialNumber,
        String problem,

        /**
         * The board's Solution column, which is filled on only about one ticket in eight.
         * It is meant to be a status log built from consecutive snapshots rather than
         * typed, so it stays mostly blank until the daily sync has run for a while.
         */
        String solution,

        LocalDate openDate,
        LocalDate reOnSite,

        /** Inclusive of the open day; null when there is no open date to count from. */
        Integer days,

        SlaStatus sla,

        /** {@link SlaStatus#label()}, so the sheet and the screen print one string. */
        String slaLabel,

        /** Not printed. Present so a blank SLA can say why it is blank. */
        String province,

        /** Not printed. Links a row back to the ticket a correction belongs on. */
        String sourceItemId
) {

    public static CaseReportRow of(int no,
                                   String project,
                                   String branch,
                                   String robot,
                                   String serialNumber,
                                   String problem,
                                   String solution,
                                   LocalDate openDate,
                                   LocalDate reOnSite,
                                   Integer days,
                                   SlaStatus sla,
                                   String province,
                                   String sourceItemId) {
        return new CaseReportRow(no, project, branch, robot, serialNumber, problem, solution,
                openDate, reOnSite, days, sla, sla == null ? "" : sla.label(),
                province, sourceItemId);
    }
}
