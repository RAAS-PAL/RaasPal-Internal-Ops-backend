package com.raaspal.robotrecommendation.pm.entity;

import java.util.Locale;

/**
 * The handful of states the planner actually needs, derived once at sync time
 * from monday's free-form status label.
 *
 * <p>Bucketing here rather than in the UI means the grid never string-matches a
 * Thai or English label. The two boards do not even agree on their labels -
 * Cleaning uses Planning/Working on it/Done, Delivery adds Waiting on approval
 * and On Hold - and a renamed label on either board would otherwise silently
 * recolour the whole planner.
 *
 * <p>OVERDUE is deliberately absent: it is a function of the plan date and today,
 * so storing it would make every row wrong the moment the date passes. It is
 * computed at query time instead.
 */
public enum PmStatusBucket {

    /** Visit done. The one bucket that is safe to read as "no longer owed". */
    COMPLETED,

    /** Engineer is on it now. */
    IN_PROGRESS,

    /** Has a plan date and is waiting to happen - includes waiting-on-approval and on-hold. */
    PLANNED,

    /** No usable status. Paired with a missing plan date this is the real blind spot. */
    UNPLANNED;

    /**
     * Maps a raw monday status label onto a bucket.
     *
     * <p>Unknown labels fall to PLANNED rather than UNPLANNED: a row somebody
     * gave a status to is work that exists, and burying it in the "nobody has
     * touched this" bucket would hide it from the planner.
     */
    public static PmStatusBucket fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNPLANNED;
        }
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case "done", "completed", "complete", "เสร็จ", "เสร็จสิ้น" -> COMPLETED;
            case "working on it", "in progress", "กำลังดำเนินการ" -> IN_PROGRESS;
            case "" -> UNPLANNED;
            default -> PLANNED;
        };
    }
}
