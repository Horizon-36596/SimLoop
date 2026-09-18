package org.horizon36596.simloop.config;

/**
 * Season-glue interface: the two numbers that describe a degree of freedom which <b>never runs out of
 * travel</b> — a continuous-rotation ejector servo, an intake roller, a flywheel (architecture §3, domain
 * R6/R7).
 *
 * <p>It is a {@link MechanismSimConfig} with the three values that only make sense for a bounded, gravity
 * loaded mechanism already filled in, so the season layer types a time constant and a top speed and
 * nothing else:
 *
 * <table border="1">
 *   <caption>What this interface fixes, and why</caption>
 *   <tr><th>Value</th><th>Fixed at</th><th>Why</th></tr>
 *   <tr><td>{@link #minPosition()}</td><td>{@code Double.NEGATIVE_INFINITY}</td>
 *       <td rowspan="2">There is no hard stop in either direction — the shaft just keeps turning.
 *           {@code Mechanism1DofPlant} accepts these two infinities and switches its end-limit clamp
 *           off.</td></tr>
 *   <tr><td>{@link #maxPosition()}</td><td>{@code Double.POSITIVE_INFINITY}</td></tr>
 *   <tr><td>{@link #gravityHoldPowerFraction()}</td><td>{@code 0.0}</td>
 *       <td>A continuously rotating shaft has no height to sag from. Gravity on this kind of actuator is
 *           a drag torque, which is already inside {@link #maxSpeed()} — the speed it actually reaches
 *           loaded. Subtracting a constant effort tax as well would double-count it.</td></tr>
 * </table>
 *
 * <p><b>Position means accumulated travel here, not a place.</b> The plant's {@code getPosition()} grows
 * without bound while the actuator runs, so the natural unit is output revolutions for a spinning shaft,
 * or inches of belt for a linear ejector — whichever the season measures. A caller wanting "how far since
 * the eject started" subtracts the reading it took when the eject started; nothing resets on its own,
 * because a real shaft's travel does not.
 *
 * <p><b>This is not a claim that the ejector is precise.</b> A continuous-rotation servo has no position
 * feedback at all ({@code docs/specs/deposit.md} §3), so nothing on the robot can read this number — an
 * eject is commanded as a <em>duration</em>. The travel modelled here exists so the sim can say how much
 * shaft went by in that duration, and so a drawing can turn.
 *
 * <p>Units are the actuator's own travel unit and seconds.
 */
public interface ContinuousRotationSimConfig extends MechanismSimConfig {

    /**
     * First-order time constant (s, &gt; 0): how quickly the actuator's speed catches up with a commanded
     * power step. For a small CR servo this is a small fraction of a second; it is the Phase-4 calibration
     * surface along with {@link #maxSpeed()}.
     */
    @Override
    double timeConstant();

    /**
     * Top travel rate (travel units/s, &gt; 0) at full commanded power — the free speed the actuator
     * reaches with the load it normally carries. Measured, not derived from a datasheet: a CR servo's
     * published no-load speed is faster than anything with a mechanism on it ever turns.
     */
    @Override
    double maxSpeed();

    /**
     * {@code Double.NEGATIVE_INFINITY}: no hard stop on this side. Do not override — and see the note on
     * {@link #gravityHoldPowerFraction()} for what "do not" is and is not worth here.
     */
    @Override
    default double minPosition() {
        return Double.NEGATIVE_INFINITY;
    }

    /** {@code Double.POSITIVE_INFINITY}: no hard stop on this side. Do not override. */
    @Override
    default double maxPosition() {
        return Double.POSITIVE_INFINITY;
    }

    /**
     * {@code 0.0} — see the table in the class javadoc for why. Do not override.
     *
     * <p><b>"Do not override" here is a rule for a human, not a guarantee the compiler makes.</b> These
     * three are ordinary interface defaults, and this project targets Java 8, which has no {@code sealed}
     * and no way to make a default final — so an implementer who overrides one gets no error. That is
     * worth knowing rather than glossing, because the honest reading of this interface is "the three
     * numbers you should not have to type", not "the three numbers that cannot be wrong".
     *
     * <p>The one override that would be actively harmful is already caught elsewhere:
     * {@code Mechanism1DofPlant}'s constructor rejects a non-zero gravity paired with an infinite
     * {@code minPosition}, because that models a mechanism that sags forever with nothing to land on.
     * Overriding a limit to a finite number is merely pointless — it describes a bounded mechanism, which
     * is what {@link MechanismSimConfig} is already for.
     */
    @Override
    default double gravityHoldPowerFraction() {
        return 0.0;
    }
}
