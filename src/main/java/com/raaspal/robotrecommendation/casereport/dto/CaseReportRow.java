package com.raaspal.robotrecommendation.casereport.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 *
 * <p>Five fields belong to the {@code RAW_AOTGA} sheet alone — {@link #requiredPart},
 * {@link #waiting}, {@link #waitingFrom}, {@link #partReceived} and
 * {@link #agingAfterReceived}. That sheet tracks spare-part turnaround rather than an SLA,
 * so it prints these in place of Solution, RE On Site and SLA. They are null on every other
 * sheet's rows, and on rows frozen before the fields existed, which the JSON reader
 * tolerates because a record component absent from the stored document reads as null.
 *
 * <p>{@link #board} belongs to the On Hold sheet alone. That sheet is the only one that
 * reads two boards, and it is the reviewer's filter — cleaning or delivery — and the
 * board a row's ticket link opens. Null everywhere else, for the same reason as above.
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

        /** AOTGA only. The board's Spare Parts Name — what was ordered for this case. */
        String requiredPart,

        /** AOTGA only. What the case is waiting on, in the RE team's words. */
        String waiting,

        /** AOTGA only. Whose court the wait is in: AOTGA, the supplier, or RAASPAL. */
        String waitingFrom,

        /** AOTGA only. When the part arrived; null while it is still on its way. */
        LocalDate partReceived,

        /**
         * AOTGA only. Days since {@link #partReceived}, counted the same way as
         * {@link #days} — the received day itself is not counted. Null until a part is
         * received.
         */
        Integer agingAfterReceived,

        /** Not printed. Present so a blank SLA can say why it is blank. */
        String province,

        /**
         * On Hold only: {@link #BOARD_CLEANING} or {@link #BOARD_DELIVERY}, the board the
         * ticket came from. Null on every single-board sheet.
         */
        String board,

        /**
         * Not printed. Whose hold a held case is — {@link #HELD_BY_CUSTOMER} when the
         * board's Status says On Hold, {@link #HELD_BY_RAASPAL} when only Sup Status does
         * (see {@code SlaCalculator.heldBy}). Drives the summary's two on-hold counts. Null
         * when the case is not held, and on rows frozen before the field existed.
         */
        String heldBy,

        /**
         * Not printed. Links a row back to the ticket a correction belongs on. A row a
         * person added by hand has no ticket and carries a {@link #MANUAL_PREFIX} id
         * instead, which is how it is told apart from a board row.
         */
        String sourceItemId,

        /**
         * Not printed. True once a person has saved a change to this row. An edited row is
         * kept as it is when the report is regenerated from the board; the rest are
         * rebuilt.
         */
        boolean edited,

        /**
         * Not printed, and not on the sheet at all. True once a person has taken this
         * board row off the report. The row is kept, hidden, so a regeneration knows
         * not to bring it back and so the removal can be undone. Numbered 0 while hidden.
         * A row added by hand is deleted outright instead — nothing would bring it back.
         */
        boolean removed
) {

    /** Id prefix of a row added by hand rather than read from the board. */
    public static final String MANUAL_PREFIX = "manual-";

    /** {@link #board} values. Strings, not an enum, so a stored row never fails to read. */
    public static final String BOARD_CLEANING = "CLEANING";
    public static final String BOARD_DELIVERY = "DELIVERY";

    /** {@link #heldBy} values, strings for the same reason as {@link #board}. */
    public static final String HELD_BY_CUSTOMER = "CUSTOMER";
    public static final String HELD_BY_RAASPAL = "RAASPAL";

    /**
     * True for a row a person added, which no board read can produce or remove.
     *
     * <p>Not serialised: Jackson would write it as a {@code manual} property and then fail
     * to read the stored JSON back, since the record has no such component. Readers look
     * at the id prefix instead.
     */
    @JsonIgnore
    public boolean isManual() {
        return sourceItemId != null && sourceItemId.startsWith(MANUAL_PREFIX);
    }

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
                null, null, null, null, null,
                province, null, null, sourceItemId, false, false);
    }

    /**
     * A row for the AOTGA sheet, which carries part-tracking fields and no solution, RE On
     * Site or province — see the class note.
     */
    public static CaseReportRow ofAotga(int no,
                                        String project,
                                        String robot,
                                        String serialNumber,
                                        String problem,
                                        LocalDate openDate,
                                        Integer days,
                                        SlaStatus sla,
                                        String requiredPart,
                                        String waiting,
                                        String waitingFrom,
                                        LocalDate partReceived,
                                        Integer agingAfterReceived,
                                        String sourceItemId) {
        return new CaseReportRow(no, project, null, robot, serialNumber, problem, null,
                openDate, null, days, sla, sla == null ? "" : sla.label(),
                requiredPart, waiting, waitingFrom, partReceived, agingAfterReceived,
                null, null, null, sourceItemId, false, false);
    }

    /** The same row under a different number, for renumbering after rows come and go. */
    public CaseReportRow withNo(int newNo) {
        return new CaseReportRow(newNo, project, branch, robot, serialNumber, problem,
                solution, openDate, reOnSite, days, sla, slaLabel,
                requiredPart, waiting, waitingFrom, partReceived, agingAfterReceived,
                province, board, heldBy, sourceItemId, edited, removed);
    }

    /** The same row stamped with the board it came from, for the On Hold sheet. */
    public CaseReportRow withBoard(String newBoard) {
        return new CaseReportRow(no, project, branch, robot, serialNumber, problem,
                solution, openDate, reOnSite, days, sla, slaLabel,
                requiredPart, waiting, waitingFrom, partReceived, agingAfterReceived,
                province, newBoard, heldBy, sourceItemId, edited, removed);
    }

    /** The same row marked with whose hold it is, from the board's two status columns. */
    public CaseReportRow withHeldBy(String newHeldBy) {
        return new CaseReportRow(no, project, branch, robot, serialNumber, problem,
                solution, openDate, reOnSite, days, sla, slaLabel,
                requiredPart, waiting, waitingFrom, partReceived, agingAfterReceived,
                province, board, newHeldBy, sourceItemId, edited, removed);
    }

    /** The same row, taken off the sheet or put back on it. */
    public CaseReportRow withRemoved(boolean nowRemoved) {
        return new CaseReportRow(no, project, branch, robot, serialNumber, problem,
                solution, openDate, reOnSite, days, sla, slaLabel,
                requiredPart, waiting, waitingFrom, partReceived, agingAfterReceived,
                province, board, heldBy, sourceItemId, edited, nowRemoved);
    }
}
