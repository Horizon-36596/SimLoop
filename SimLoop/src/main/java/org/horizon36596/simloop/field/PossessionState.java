package org.horizon36596.simloop.field;

/**
 * The complete, fixed set of possession states one game piece can be in. A piece is in exactly one of
 * these at all times, and it only ever changes through {@link GamePieceTracker}, which logs the change
 * (domain R5 — every transition is in the RLOG and identical on replay).
 *
 * <p><b>This is a bookkeeping model, not physics.</b> The human ruled on 2026-09-03 that game pieces are
 * modelled as possession events plus trigger volumes rather than as simulated balls: nothing here rolls,
 * bounces, jams, or collides, because domain R6 forbids rigid-body and contact physics. A piece is a
 * position, a size, and one of these three states.
 *
 * <p>Season-agnostic (domain R7): there is no state here that only one game's pieces could be in, and the
 * set is deliberately small — three states a student can hold in their head, not a graph of handoffs.
 */
public enum PossessionState {

    /**
     * Sitting on the field at its own position, available to be acquired. This is where a piece starts and
     * where an ejected piece returns to.
     */
    LOOSE,

    /**
     * Carried by the robot. A held piece's logged field position is the robot's field position — the model
     * deliberately does not track where inside the robot a piece sits, because that would need the contact
     * physics R6 rules out. The count of held pieces is what the capacity invariant bounds.
     */
    HELD,

    /**
     * Released into a scoring location and out of play. Terminal: a scored piece is never acquired again,
     * so a trigger volume sweeping over one does nothing.
     */
    SCORED
}
