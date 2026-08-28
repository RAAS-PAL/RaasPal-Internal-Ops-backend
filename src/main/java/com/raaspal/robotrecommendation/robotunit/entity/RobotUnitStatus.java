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
 * <p>Two store-room states were added later, in V37, at the warehouse's request:
 * {@link #UNDER_REPAIR} and {@link #RETURNED_FROM_CUSTOMER}. They describe a robot on
 * our own premises that is not sellable, so they widen {@link #isStockRoomStatus} only
 * — {@link #isWarehouseVisible} and {@link #isAvailable} are untouched, and the fleet
 * still accepts exactly the values it always did.
 *
 * <p>What a technician actually did remains a CM report ticket. These say only where
 * the unit is, never what is wrong with it.
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
    SOLD,

    /**
     * On our premises but not fit to deploy — awaiting or undergoing service.
     *
     * <p>Store-room only. A robot away for repair has not changed hands, so it never
     * belongs on a {@code robot_units} row; {@link #isWarehouseVisible} continues to
     * exclude it and only {@link #isStockRoomStatus} admits it.
     *
     * <p>The note above about repairs being absent still holds for the fleet: what a
     * technician did remains a CM report ticket. This records only that the unit is
     * off the sellable shelf while that happens.
     */
    UNDER_REPAIR,

    /**
     * Back from a customer and not yet triaged into stock, repair or disposal.
     *
     * <p>Store-room only, for the same reason as {@link #UNDER_REPAIR}. Distinct from
     * IN_STOCK because it has not been checked over yet, and promising it to another
     * customer before someone has looked at it is the mistake this prevents.
     */
    RETURNED_FROM_CUSTOMER;

    /**
     * Statuses the <em>fleet's</em> own stock endpoints accept.
     *
     * <p>Unchanged when the store-room states were added, and deliberately so: this
     * guards {@code RobotUnitService} in five places, and widening it would let a
     * {@code robot_units} row take a state that only means something on a shelf.
     */
    public boolean isWarehouseVisible() {
        return this == IN_STOCK || this == DEMO;
    }

    /**
     * Statuses RIMS records against a shelf — the four the warehouse works in.
     *
     * <p>Wider than {@link #isWarehouseVisible}, and kept separate from it rather than
     * replacing it, because the two answer different questions: that one asks what the
     * fleet may store, this one asks what the store room may hold.
     */
    public boolean isStockRoomStatus() {
        return this == IN_STOCK
                || this == DEMO
                || this == UNDER_REPAIR
                || this == RETURNED_FROM_CUSTOMER;
    }

    /** Available to deploy or sell right now. Excludes demo units. */
    public boolean isAvailable() {
        return this == IN_STOCK;
    }
}
