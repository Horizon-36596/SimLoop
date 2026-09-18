package org.horizon36596.simloop.config;

/**
 * Season-glue interface: the parameterized first-order dynamics + end-limits of one 1-DOF mechanism,
 * supplied by the season layer in {@code TeamCode} (architecture §3, domain R6/R7).
 *
 * <p>The core never hard-codes a season's mechanism constants; it asks for them through this interface,
 * so a {@code Mechanism1DofPlant} is fully described by these five numbers. They are the calibration
 * surface: Phase-4 sim2real fits {@link #timeConstant()} and {@link #maxSpeed()} to real-robot data
 * without touching the plant (domain R6, {@code vision.md} §6).
 *
 * <p>Units are the mechanism's own position unit (inches for a linear slide, radians for an arm) and
 * seconds; the plant is unit-agnostic as long as every value here shares one position unit.
 */
public interface MechanismSimConfig {

    /**
     * First-order time constant (s, &gt; 0): the single pole of the mechanism's response. A commanded
     * step reaches ~63% of the way to target in one time constant and settles in ~4–5 (domain R6).
     *
     * @return the time constant, in seconds; strictly positive
     */
    double timeConstant();

    /**
     * Maximum position rate (units/s, &gt; 0): the force/effort limit expressed as the top speed an
     * overdamped first-order mechanism can move. It saturates large commanded steps so the response
     * ramps at this rate rather than jumping — the calibration-ready image of a real actuator's
     * force/current ceiling (domain R6).
     *
     * @return the top position rate, in the mechanism's own position units per second; strictly positive
     */
    double maxSpeed();

    /** {@return the lower end-limit, in the mechanism's own position units}: position can never integrate below this under any command (domain R6) */
    double minPosition();

    /** {@return the upper end-limit, in the mechanism's own position units}: position can never integrate above this under any command (domain R6) */
    double maxPosition();

    /**
     * How much of the motor's full effort the mechanism's own weight takes away, as a fraction in
     * {@code [0, 1)} — a vertical slide carrying a load needs a continuous power just to stay where it is,
     * and gets nothing back when it lets go.
     *
     * <p><b>Sign convention: always report a POSITIVE number.</b> The plant subtracts it, so gravity always
     * pulls toward {@link #minPosition()}. A mechanism gravity does not load — a turret, a horizontal
     * extension, a flywheel — reports {@code 0.0}, and the plant then behaves exactly as it did before this
     * method existed.
     *
     * <p>This is still parameterized first-order dynamics, not rigid-body physics (domain R6): one scalar
     * standing for "weight costs this much effort", not a force model. It is the number a gravity
     * feedforward in the robot's own controller has to cancel, which is exactly why the plant has to model
     * it — a plant with no gravity holds any height at zero power, so a controller with no feedforward would
     * look perfect in sim and sag on the robot.
     *
     * @return the held-power fraction, unitless, in {@code [0, 1)}
     */
    double gravityHoldPowerFraction();
}
