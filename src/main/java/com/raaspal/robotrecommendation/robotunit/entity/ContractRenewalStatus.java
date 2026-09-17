package com.raaspal.robotrecommendation.robotunit.entity;

/**
 * Where the customer success team is with a contract that is ending. Stored on the
 * deployment for its current term; {@link #NOT_CONTACTED} is the unset state and is
 * what a term starts as.
 */
public enum ContractRenewalStatus {
    NOT_CONTACTED,
    CONTACTED,
    WILL_RENEW,
    WILL_NOT_RENEW
}
