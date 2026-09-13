package com.raaspal.robotrecommendation.casereport.dto;

import com.raaspal.robotrecommendation.casereport.service.SlaStatus;

import java.time.LocalDate;

/**
 * A person's corrections to one generated row, as the edit form sends them.
 *
 * <p>Every printed cell is here and every one is replaced by what arrives, blank meaning
 * blank — the form always submits the whole row, so there is no "unchanged" to express.
 * The two exceptions are the derived cells: a null {@link #days} or {@link #sla} means
 * "work it out from the Open Date", because that is what a reviewer who only corrected the
 * date expects to happen, and typing the arithmetic in by hand is how the two get out of
 * step.
 */
public record CaseRowEdit(
        String project,
        String branch,
        String robot,
        String serialNumber,
        String problem,
        String solution,
        LocalDate openDate,
        LocalDate reOnSite,

        /** Null: recalculated from {@link #openDate}. A value: printed as given. */
        Integer days,

        /** Null: recalculated from {@link #openDate}. A value: printed as given. */
        SlaStatus sla,

        /**
         * Not printed, but decides the SLA threshold. Editable because a blank one is the
         * commonest reason for a row with no verdict, and a row added by hand has no
         * ticket to take it from.
         */
        String province
) {
}
