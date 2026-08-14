package com.raaspal.robotrecommendation.common.enums;

/**
 * The classes of machine RAASPAL supplies.
 *
 * <p>Stored as a plain {@code VARCHAR(20)} with no check constraint, so adding a
 * value needs no migration — but the width is real: a new name must fit in twenty
 * characters, and {@code CLEANING_EQUIPMENT} at eighteen is the longest so far.
 *
 * <p>Renaming a value is the expensive direction, not adding one. It takes a data
 * migration over every table holding the old string — see
 * {@code V7__rename_concierge_to_mowing.sql}, which had to touch {@code robots}
 * alone; there are now four such columns.
 */
public enum RobotType {
    CLEANING,
    DELIVERY,
    MOWING,
    SECURITY,
    COOKING,
    /** Non-autonomous cleaning machines — scrubbers, vacuums, sweepers. */
    CLEANING_EQUIPMENT,
    RECEPTION
}
