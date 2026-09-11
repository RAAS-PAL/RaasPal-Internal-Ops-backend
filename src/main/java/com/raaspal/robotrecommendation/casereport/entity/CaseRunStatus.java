package com.raaspal.robotrecommendation.casereport.entity;

/**
 * Where a generated report stands. Mirrors the CHECK constraint on
 * {@code case_report_run.status} (V38).
 *
 * <p>The important line this enum draws is between {@link #SENT} and everything else.
 * Before sending, a report is a draft and regenerating it should pick up whatever has
 * changed on the board. After sending, it is a record of what a customer received — a
 * LINE message cannot be recalled — so its rows must never move again.
 */
public enum CaseRunStatus {

    /** Being built. A run left here is a crash, not a state anyone chose. */
    GENERATING,

    /** Built, frozen, waiting for a human. Regenerating replaces it. */
    AWAITING_APPROVAL,

    /** Delivered. Immutable from here: this is what the customer actually got. */
    SENT,

    FAILED,

    /** Generated, then rejected by a reviewer. Kept so the rejection is on record. */
    DISCARDED;

    /**
     * Whether regenerating this date may overwrite the stored rows.
     *
     * <p>Everything except {@link #SENT}. A draft should reflect the board; a sent report
     * should reflect history.
     */
    public boolean isReplaceable() {
        return this != SENT;
    }
}
