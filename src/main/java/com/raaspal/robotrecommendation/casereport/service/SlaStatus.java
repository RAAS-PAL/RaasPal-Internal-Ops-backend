package com.raaspal.robotrecommendation.casereport.service;

/**
 * Whether a case is inside its service-level agreement.
 *
 * <p>Named for what it means, not for the colour it prints as. The report renders
 * 🟢/🔴/🟡 and a blank, but a colour is a rendering decision and this is a claim about
 * who is late — which ends up in front of a customer.
 */
public enum SlaStatus {

    /** Inside the agreed number of days. */
    WITHIN,

    /** Past it. A public statement that the delay is RAASPAL's, so it must be right. */
    BREACHED,

    /**
     * Paused, and therefore neither. "On Hold" means the clock is not RAASPAL's to run
     * — typically waiting on the customer — so the case is not judged either way.
     */
    ON_HOLD,

    /**
     * Not determinable, and deliberately left uncoloured.
     *
     * <p>Reached when the open date is missing, or when the report's thresholds differ
     * by province and no province is recorded. Blank is the honest answer: showing a
     * case as green because a field was empty would tell a customer their case is on
     * time when nobody actually knows, and that error is invisible where a blank cell
     * gets filled in.
     */
    UNKNOWN;

    /**
     * What the SLA column prints, worded exactly as the live reports word it.
     *
     * <p>Taken verbatim from the 09 September 2026 workbook, lower-case "over" and all:
     * {@code over SLA}, {@code Within SLA}, {@code On Hold}. The recipients read this
     * column every morning, so tidying the capitalisation would be a visible change to a
     * customer-facing document for no benefit.
     *
     * <p>{@link #UNKNOWN} prints empty rather than a word. A blank cell is a question
     * somebody fills in; "Unknown" reads like a verdict.
     */
    public String label() {
        return switch (this) {
            case WITHIN -> "Within SLA";
            case BREACHED -> "over SLA";
            case ON_HOLD -> "On Hold";
            case UNKNOWN -> "";
        };
    }
}
