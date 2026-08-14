package com.raaspal.robotrecommendation.robotunit.entity;

/**
 * Where a physical robot stands commercially, added in V28.
 *
 * <p>Only three values, and deliberately so. A <strong>SOLD</strong> robot keeps its
 * active {@link Deployment}: RAASPAL still syncs its telemetry and sends the customer
 * monthly reports after ownership transfers. So "sold" describes the commercial
 * arrangement, not the robot's whereabouts — which is why RIMS can list warehouse
 * stock with the single condition {@code status = IN_STOCK} and correctly exclude
 * both rented and sold units, with no special cases.
 *
 * <p>Repairs are intentionally absent. Service work is tracked as CM report tickets,
 * a separate concern, and a robot away for repair has not changed hands.
 */
public enum RobotUnitStatus {

    /** In our own premises and available to deploy or sell. */
    IN_STOCK,

    /**
     * Out with a customer on a trial or demonstration. Still RAASPAL's asset and
     * still on the books — but not available, so it must not be counted as sellable
     * stock or promised to another customer.
     *
     * <p>Visible in RIMS alongside IN_STOCK, unlike RENT and SOLD: those are at a
     * customer under a commercial agreement and are the account team's concern, but
     * a demo unit is coming back and the warehouse needs to know it exists.
     */
    DEMO,

    /** At a customer under a rental agreement; RAASPAL still owns it. */
    RENT,

    /** Ownership transferred to the customer. Still monitored and reported on. */
    SOLD;

    /** Statuses the warehouse cares about — what RIMS lists. */
    public boolean isWarehouseVisible() {
        return this == IN_STOCK || this == DEMO;
    }

    /** Available to deploy or sell right now. Excludes demo units. */
    public boolean isAvailable() {
        return this == IN_STOCK;
    }
}
