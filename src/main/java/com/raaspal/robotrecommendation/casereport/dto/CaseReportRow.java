package com.raaspal.robotrecommendation.casereport.dto;

import com.raaspal.robotrecommendation.casereport.service.SlaStatus;

import java.time.LocalDate;

/**
 * One printed line of a pending-case report.
 *
 * <p>The field order is the column order of the {@code Raw_Delivery} sheet, so the Excel
 * writer and the review table walk the same list and cannot disagree about layout.
 *
 * <p>Three fields are carried that the sheet does not print. {@link #province} explains a
 * blank SLA — without it a reviewer sees an empty cell and no reason for it.
 * {@link #sourceItemId} is what a reviewer clicks to open the ticket on monday, and what an
 * edit is addressed to. And {@link #edited} records that a person changed the row after it
 * was generated, which is what stops a regeneration from writing over their correction.
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
         * The board's Solution cell when somebody typed one, which is about one ticket in
         * eight, and otherwise the model's paraphrase of the comment thread: one dated
         * entry per step. Null when neither has anything to say.
         */
        String solution,

        LocalDate openDate,
        LocalDate reOnSite,

        /**
         * Whole days since the open date, not counting it: a case opened today reads 0.
         * Null when there is no open date to count from.
         */
        Integer days,

        SlaStatus sla,

        /** {@link SlaStatus#label()}, so the sheet and the screen print one string. */
        String slaLabel,

        /** Not printed. Present so a blank SLA can say why it is blank. */
        String province,

        /** Not printed. Links a row back to the ticket a correction belongs on. */
        String sourceItemId,

        /**
         * Not printed. True once a person has saved a change to this row. An edited row is
         * kept as it is when the report is regenerated from the board; the rest are
         * rebuilt.
         */
        boolean edited
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
                province, sourceItemId, false);
    }

    /** The same row under a different number, for renumbering after rows come and go. */
    public CaseReportRow withNo(int newNo) {
        return new CaseReportRow(newNo, project, branch, robot, serialNumber, problem,
                solution, openDate, reOnSite, days, sla, slaLabel, province, sourceItemId,
                edited);
    }
}
