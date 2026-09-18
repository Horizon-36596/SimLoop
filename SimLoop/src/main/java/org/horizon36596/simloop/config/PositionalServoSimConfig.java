package org.horizon36596.simloop.config;

/**
 * Season-glue interface: everything a {@code PositionalServoPlant} needs to turn a servo command in
 * {@code [0, 1]} into a real mechanism position that takes time to get there (architecture §3, domain
 * R6/R7).
 *
 * <p>The first four numbers are inherited from {@link MechanismSimConfig} and mean exactly what they mean
 * there, so a servo-driven joint and a motor-driven one are described by the same vocabulary:
 * {@link #timeConstant()} is the single pole, {@link #maxSpeed()} is the travel-rate ceiling, and
 * {@link #minPosition()}/{@link #maxPosition()} are hard end stops. This interface adds the one thing a
 * motor does not need: <b>what the servo's command actually means in mechanism units</b>.
 *
 * <p><b>Why the map has to live here.</b> A servo is commanded in a unitless {@code [0, 1]}; a drawing
 * needs radians (or inches). Only the season knows that {@code 0.05} is "transfer" and {@code 0.60} is
 * "deposit". The core refuses to guess, so it asks for the two endpoints of a straight line and
 * interpolates.
 *
 * <p><b>The two endpoints are measured at the horn's full mechanical travel</b>, not at whatever a
 * particular {@code setPosition} call happens to request. So they are the numbers CAD would give you with
 * the servo swept end to end. A servo narrowed with {@code scaleRange} then reaches only <em>part</em> of
 * that sweep, which is exactly what a narrowed servo does on the robot — the plant does not need, and
 * must not be given, a second pair of endpoints for the narrowed case.
 *
 * <p>Units: the four inherited numbers and the two endpoints are in the mechanism's own position unit
 * (radians for an arm pivot, inches for a linear pusher) and seconds, and all of them must share one
 * position unit. {@link #commandDeadband()} and {@link #commandQuantum()} are the exception and are
 * deliberately NOT in that unit: they describe the servo's command channel, so they are fractions of its
 * {@code [0, 1]} sweep. Each says so again on itself, because getting that wrong is a silent factor-of-the-
 * sweep error rather than a compile failure.
 */
public interface PositionalServoSimConfig extends MechanismSimConfig {

    /**
     * Mechanism position (units) the joint physically reaches when the servo's scaled command is
     * {@code 0.0}.
     *
     * <p>This may be <b>greater</b> than {@link #positionAtCommandOne()} — that is simply a servo mounted
     * so that increasing command drives the mechanism the other way, which is common and needs no
     * separate "reversed" flag.
     *
     * @return the mechanism position at command {@code 0.0}, in the mechanism's own position units
     */
    double positionAtCommandZero();

    /**
     * Mechanism position (units) the joint physically reaches when the servo's scaled command is
     * {@code 1.0}. Must differ from {@link #positionAtCommandZero()} — a servo whose two endpoints are the
     * same position is a joint that cannot move, which is a configuration mistake rather than a mechanism.
     *
     * <p>Neither endpoint has to lie inside {@code [minPosition, maxPosition]}: a mechanical hard stop
     * reached before the servo's full sweep is a real and common build, and the plant simply stops there.
     *
     * @return the mechanism position at command {@code 1.0}, in the mechanism's own position units
     */
    double positionAtCommandOne();

    /**
     * How far the commanded position has to be from where the horn already sits before the servo bothers
     * to move, in <b>command units</b> of the servo's {@code [0, 1]} sweep. Defaults to {@code 0.0}, which
     * is a perfect servo that chases the last thousandth.
     *
     * <p><b>This is the single most important number for anything that has to hold an aim.</b> A real
     * hobby servo compares its commanded pulse against its own internal pot and does nothing at all while
     * the two are within its deadband. The joint therefore parks somewhere inside a band around the
     * target and stays there, with a steady-state error that never integrates away, however good the
     * control law above it is. A servo modelled without one converges to the target every time and makes
     * pointing problems look solved when they are not.
     *
     * <p><b>MEASURE this one; do not compute it from a datasheet.</b> A servo's advertised deadband is a
     * pulse width in microseconds, and turning that into command units needs the pulse range the port is
     * actually configured for — which is per-servo on an FTC hub, not a fixed span, so the same
     * microsecond figure maps to different command-unit numbers on different robots. The number that
     * matters is the one a characterization routine measures directly: step the command in small
     * increments, record where motion actually starts, and the width of the interval that produced no
     * motion is this value. Multiply by {@code |positionAtCommandOne() - positionAtCommandZero()|} to read
     * it in mechanism units.
     *
     * <p>Must be finite and {@code >= 0}, and smaller than the full sweep, or the joint could never move
     * at all.
     *
     * @return the deadband width, in servo command units (the same {@code [0, 1]} scale as the command)
     */
    default double commandDeadband() {
        return 0.0;
    }

    /**
     * The step size the servo's command is rounded to before it is used, in <b>command units</b> of the
     * {@code [0, 1]} sweep. Defaults to {@code 0.0}, which means no rounding at all.
     *
     * <p>A servo command does not arrive as a real number. Somewhere between the call and the horn it is
     * reduced to a finite set of steps — the pulse widths the controller can generate, and on a digital
     * servo the resolution of its own internal position feedback — so two commands differing in the
     * seventh decimal place produce the same resting place. This parameter is the observed size of that
     * step, whichever of those causes it; it is not an attempt to model one of them specifically, and a
     * characterization routine cannot tell them apart anyway.
     *
     * <p>It is a different floor from the deadband: quantization is the resolution of what can be <i>asked
     * for</i>, the deadband is how much of what is asked for gets <i>ignored</i>. On a digital servo the
     * two can have a common cause, so measuring both from the same sweep and then adding them would
     * double-count — take the deadband as the width of the no-motion interval and the quantum as the
     * spacing between distinct resting places.
     *
     * <p>Distinguishing them matters because they fail differently. A coarse quantum makes the achievable
     * positions a grid, so the best possible aim is half a quantum off and no smoothing helps. A wide
     * deadband makes the joint stick wherever it happened to stop, so the error depends on which way it
     * approached. A control law that fixes one does not fix the other.
     *
     * <p>Must be finite and {@code >= 0}, and smaller than the full sweep.
     *
     * @return the command step size, in servo command units; {@code 0.0} means continuous
     */
    default double commandQuantum() {
        return 0.0;
    }

    /**
     * Always {@code 0.0} for a servo-driven joint, and <b>not</b> because servo-driven joints are
     * weightless. {@code PositionalServoPlant} does not read this number at all, so a servo arm that
     * really is gravity-loaded gets no sag in sim no matter what is reported here.
     *
     * <p>It is defaulted rather than left abstract so that nobody has to type a number the plant throws
     * away, and it is documented rather than quietly inherited so that nobody spends an afternoon
     * wondering why their 0.35 changed nothing. Closing the gap means teaching the servo plant the same
     * effort tax {@code Mechanism1DofPlant} already applies -- tracked as {@code docs/BACKLOG.md} B27.
     *
     * <p>Overriding it today is harmless and equally useless. Do not.
     */
    @Override
    default double gravityHoldPowerFraction() {
        return 0.0;
    }
}
