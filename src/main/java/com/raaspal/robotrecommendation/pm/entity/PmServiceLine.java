package com.raaspal.robotrecommendation.pm.entity;

/**
 * Which PM board a contract came from. The two lines are maintained as separate
 * monday boards with separate column ids, and the RE team plans them separately,
 * so the distinction is worth keeping rather than merging on import.
 */
public enum PmServiceLine {
    CLEANING,
    DELIVERY
}
