package com.raaspal.robotrecommendation.inventory.entity;

/**
 * Whether a robot on the shelf is still in its carton.
 *
 * <p>Kept as its own field rather than folded into the lifecycle status: a unit can be
 * boxed in any of the four states, and combining them would turn one list of four into
 * eight, most of which never occur.
 *
 * <p>Null is a real third state, not a gap to be filled — it means nobody has looked.
 * Every row predating V37 has no packaging recorded, and defaulting them to BOX would
 * be inventing a fact about a shelf no one has checked.
 *
 * <p>One value covers the whole quantity on a row. Packaging is deliberately outside
 * the identity index (see V37), so a shelf of four units holding two boxed and two
 * unboxed cannot be told apart. That trade was made knowingly, to avoid splitting
 * every entry in two the first time someone opened a carton.
 */
public enum Packaging {

    /** Sealed in its original carton, as delivered. */
    BOX,

    /** Out of the carton — unpacked for a demo, a test, or service. */
    UNBOX
}
