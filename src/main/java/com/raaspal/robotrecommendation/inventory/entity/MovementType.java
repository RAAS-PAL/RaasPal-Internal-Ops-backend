package com.raaspal.robotrecommendation.inventory.entity;

/**
 * Why a stock level changed.
 *
 * <p>The type is descriptive, not arithmetic — direction comes from the sign of
 * {@code quantityChange}, so the ledger balance is a plain {@code SUM} with no
 * per-type branching. A RETURN that has to be booked as a correction is still a
 * RETURN; only the number decides which way the stock moved.
 */
public enum MovementType {

    /** Stock arrived — a delivery from a supplier. Positive. */
    RECEIPT,

    /** Stock left — issued to a technician or a job. Negative. */
    ISSUE,

    /**
     * A stocktake correction. The only type permitted to drive a balance negative,
     * because reality occasionally disagrees with the ledger and refusing to record
     * that would just push staff into inventing a fake RECEIPT to square it.
     */
    ADJUSTMENT,

    /** Unused stock came back. Positive. */
    RETURN
}
