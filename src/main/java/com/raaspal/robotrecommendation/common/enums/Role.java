package com.raaspal.robotrecommendation.common.enums;

public enum Role {
    ADMIN,
    RAASPAL_TEAM,
    CUSTOMER,

    /**
     * Warehouse and inventory staff (RIMS). Sees stock robots and parts; has no
     * access to the proposal, recommendation or partner surfaces.
     *
     * <p>No migration was needed to add this: {@code users.role} is a plain
     * {@code VARCHAR(20)} with no CHECK constraint, and the name fits.
     */
    INVENTORY_STAFF
}
