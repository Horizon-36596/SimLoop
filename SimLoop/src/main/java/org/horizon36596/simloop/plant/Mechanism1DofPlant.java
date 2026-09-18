package org.horizon36596.simloop.plant;

import com.qualcomm.robotcore.hardware.DcMotorSimple;
import org.horizon36596.simloop.config.MechanismSimConfig;

/**
 * Generic 1-DOF mechanism plant (fakehardware-plant §4, domain R6): the season-agnostic primitive every
 * mechanism (slide, arm, turret) is built on — one class, one config per mechanism. It is driven
 * <b>through its motor</b> — the same {@link DcMotorSimple} surface unchanged robot code touches via
 * {@code setPower} — so the team's own external-encoder + custom closed-loop algorithm runs in sim exactly
 * as on hardware (never RUN_TO_POSITION), and its gains transfer (domain R1/R6/R8). {@link #getPosition()}
 * is the mechanism position the external-encoder fake reports back for that algorithm to read (wired by
 * the season glue in Batch 2.2).
 *
 * <p><b>Model.</b> A single-state first-order velocity response to the motor's commanded power minus what
 * gravity takes, then a kinematic position integral with a hard end-limit:
 * <pre>
 *   netPower    = effectivePower - gravityHoldPowerFraction        // weight is a constant effort tax
 *   cmdVelocity = clamp(netPower, -1, 1) * maxSpeed                // power in [-1,1] -> commanded rate
 *   velocity   += (cmdVelocity - velocity) * (1 - exp(-dt/tau))    // exact first-order lag toward it
 *   position    = clamp(position + velocity*dt, minPos, maxPos)    // hard end-limit
 *   if driving into a limit: velocity = 0                          // stalled against a hard stop
 * </pre>
 * {@code effectivePower} applies the SDK direction contract (a {@code REVERSE} motor flips the sign,
 * mirroring {@code FakeMotor.update}). Under a held power the velocity converges to
 * {@code (power - gravity)*maxSpeed} with time constant {@code timeConstant} (exact exponential, so the
 * response is {@code deltaTime}-independent), giving true first-order motion rather than a kinematic jump
 * (Contract clause 2). Position can never integrate past {@code [minPosition, maxPosition]} under any
 * command (Contract clause 1). This is parameterized first-order dynamics, not rigid-body/contact physics
 * (domain R6); {@code timeConstant}/{@code maxSpeed}/{@code gravityHoldPowerFraction} are the Phase-4
 * calibration surface.
 *
 * <p><b>Why gravity is modelled here at all.</b> Without it a mechanism holds any position at zero power,
 * so a controller with no gravity feedforward converges perfectly in sim and sags on the real robot — the
 * sim would be actively lying about the one thing a loaded vertical carriage most needs checked. A
 * mechanism gravity does not load reports {@code 0.0} and the term vanishes.
 *
 * <p>The motor's own first-order speed lag (a drive {@code FakeMotor}'s {@code maxAccel}) is intentionally
 * unused here: the plant reads the <em>raw commanded power</em> and applies a single mechanism pole, so
 * the modelled dynamics stay first-order rather than compounding two lags into a second-order response.
 *
 * <p>Season-agnostic core: depends only on the SDK {@link DcMotorSimple} surface + {@link MechanismSimConfig}
 * (domain R7, SimLoop rule 2). Deterministic: state changes only in {@link #update(double)} off the injected
 * tick {@code deltaTime}, never a wall-clock, and via {@link StrictMath#exp} so replay is bit-stable across
 * JVMs (domain R5); getters are pure reads (conventions §4).
 *
 * <h2>A degree of freedom with no end stops</h2>
 * Some actuators simply never run out of travel: a continuous-rotation ejector servo, an intake roller, a
 * flywheel. Those are the same first-order model with the end-limit clamp switched off, so they are
 * modelled by this class with {@code minPosition = Double.NEGATIVE_INFINITY} and
 * {@code maxPosition = Double.POSITIVE_INFINITY} — see
 * {@link org.horizon36596.simloop.config.ContinuousRotationSimConfig}, which supplies
 * exactly those two values so no season has to type them. {@link #getPosition()} then means <b>accumulated
 * travel</b> (output revolutions for an ejector) and grows without bound, which is the honest reading of a
 * shaft that keeps turning. The two infinities are the only non-finite config values this class accepts,
 * and they are accepted only as limits; every other value must still be finite.
 *
 * <p><b>A continuous-rotation servo drives this plant directly, with no adapter.</b> The SDK's
 * {@code CRServo} extends {@link DcMotorSimple}, so {@code new Mechanism1DofPlant(fakeCrServo, config)}
 * compiles and behaves correctly: {@code CRServoImpl.getPower()} hands back the power the caller asked
 * for (its direction reversal is applied on the way in and undone on the way out), and this plant then
 * applies the direction itself, exactly as it does for a motor. Pinned by
 * {@code MultiDofMechanismPlantTest} (BACKLOG B13).
 */
public final class Mechanism1DofPlant implements OneDofPlant {

    private final DcMotorSimple motor;
    private final double timeConstant;
    private final double maxSpeed;
    private final double minPosition;
    private final double maxPosition;
    private final double gravityHoldPowerFraction;

    private double position;
    private double velocity;

    /**
     * Builds the plant for one powered 1-DOF mechanism, at rest at the clamped origin.
     *
     * @param motor  the actuator the mechanism's subsystem drives via {@code setPower} — the SAME motor
     *               instance registered in the {@code FakeHardwareMap} the robot code resolves (domain R1),
     *               so unchanged code closes the loop in sim.
     * @param config the mechanism's first-order params + end-limits (glue-injected, domain R7). Starts at
     *               rest at the clamped origin ({@code 0} clamped into {@code [min, max]}); seed a
     *               different start with {@link #setPosition(double)}.
     * @throws IllegalArgumentException if {@code motor} is null, any config value is non-finite (the end
     *                                  limits excepted — either may be an infinity, meaning no hard stop
     *                                  on that side, but neither may be {@code NaN}),
     *                                  {@code timeConstant <= 0}, {@code maxSpeed <= 0},
     *                                  {@code minPosition >= maxPosition},
     *                                  {@code gravityHoldPowerFraction} is outside {@code [0, 1)}, or it
     *                                  is non-zero while {@code minPosition} is {@code -Infinity} (a
     *                                  mechanism that sags forever with nothing to land on).
     */
    public Mechanism1DofPlant(DcMotorSimple motor, MechanismSimConfig config) {
        if (motor == null) {
            throw new IllegalArgumentException("mechanism plant needs a motor to read power from");
        }
        // Finiteness first: a NaN/Infinity config value would slip every sign/order comparison below
        // (NaN comparisons are always false; Infinity passes `> 0`) and later poison position via the
        // clamp or the lag formula — fail loud instead (fakehardware-plant §0).
        double tau = requireFinite(config.timeConstant(), "timeConstant");
        double vMax = requireFinite(config.maxSpeed(), "maxSpeed");
        // The two end limits are the ONLY config values allowed to be infinite, and only in the direction
        // that means "this degree of freedom has no hard stop on that side" (see the class javadoc). NaN
        // is still rejected, because a NaN limit slips the clamp and poisons position permanently.
        double min = requireFiniteOrInfinite(config.minPosition(), "minPosition");
        double max = requireFiniteOrInfinite(config.maxPosition(), "maxPosition");
        double gravity = requireFinite(config.gravityHoldPowerFraction(), "gravityHoldPowerFraction");
        if (tau <= 0) {
            throw new IllegalArgumentException("timeConstant must be positive, got " + tau);
        }
        if (vMax <= 0) {
            throw new IllegalArgumentException("maxSpeed must be positive, got " + vMax);
        }
        if (min >= max) {
            throw new IllegalArgumentException(
                    "minPosition must be < maxPosition, got [" + min + ", " + max + "]");
        }
        // [0, 1): negative would mean gravity HELPS the mechanism climb, and 1.0 or more would mean full
        // motor effort cannot even hold it — both are configuration mistakes, not mechanisms.
        if (gravity < 0.0 || gravity >= 1.0) {
            throw new IllegalArgumentException(
                    "gravityHoldPowerFraction must be in [0, 1), got " + gravity);
        }
        // Gravity pulls toward minPosition, so a gravity-loaded mechanism with no LOWER hard stop would
        // sag forever: at zero power it settles at -gravity*maxSpeed and keeps going, with nothing to
        // land on. That is a configuration mistake rather than a mechanism, and it is silent -- the plant
        // would just report an ever-growing negative position -- so it is rejected here. An infinite UPPER
        // limit with gravity is fine and is a real build (a winch that can be wound up forever but has a
        // floor), so only the lower one is checked.
        if (gravity > 0.0 && min == Double.NEGATIVE_INFINITY) {
            throw new IllegalArgumentException(
                    "a mechanism with no lower end-limit cannot be gravity-loaded: minPosition is "
                            + "-Infinity and gravityHoldPowerFraction is " + gravity + ", which models a "
                            + "mechanism that sags forever with nothing to land on. Either give it a real "
                            + "minPosition, or report gravityHoldPowerFraction 0.0 (which is what "
                            + "ContinuousRotationSimConfig does).");
        }
        this.motor = motor;
        this.timeConstant = tau;
        this.maxSpeed = vMax;
        this.minPosition = min;
        this.maxPosition = max;
        this.gravityHoldPowerFraction = gravity;
        this.position = clampToLimits(0.0);
        this.velocity = 0.0;
    }

    /** Advance the mechanism one tick from the motor's current commanded power (fakehardware-plant §2). */
    public void update(double deltaTime) {
        // A tick advances the clock forward by a finite amount; a non-finite or non-positive deltaTime is
        // not a real sim tick (FakeTimer is monotonic, domain R5), so treat it as no time passing. Keeps
        // any NaN/negative deltaTime from injecting a NaN or backward step into the end-limit invariant.
        if (!Double.isFinite(deltaTime) || deltaTime <= 0.0) {
            return;
        }
        // Read the command off the motor surface the robot code drives, applying the SDK direction rule.
        // A non-finite motor power is a caller bug; treat it as zero force so it cannot poison position
        // (fail-safe here — the plant does not own the motor's power contract).
        double power = motor.getPower();
        if (!Double.isFinite(power)) {
            power = 0.0;
        }
        double effectivePower = (motor.getDirection() == DcMotorSimple.Direction.REVERSE) ? -power : power;

        // The mechanism's own weight is a constant effort tax pulling toward minPosition: it is subtracted
        // from the commanded power before the power becomes a rate, so zero power means "sag", not "hold".
        // Clamped back into [-1, 1] first, so a mechanism commanded full power downward cannot be modelled
        // as falling faster than its own maxSpeed.
        double netPower = Math.max(-1.0, Math.min(1.0, effectivePower - gravityHoldPowerFraction));
        double cmdVelocity = netPower * maxSpeed;
        // Exact first-order lag of velocity toward the commanded rate (deltaTime-independent, StrictMath
        // for bit-stable cross-JVM replay).
        velocity += (cmdVelocity - velocity) * (1.0 - StrictMath.exp(-deltaTime / timeConstant));

        position = clampToLimits(position + velocity * deltaTime);
        // Hit a hard stop: the mechanism stalls, so its (external-encoder) velocity is zero, not the
        // motor's still-spinning command. Fires the moment it sits on a limit still driving into it —
        // including an exact landing — preventing wind-up when the command later reverses.
        if ((position <= minPosition && velocity < 0.0) || (position >= maxPosition && velocity > 0.0)) {
            velocity = 0.0;
        }
    }

    /** {@return the current position in mechanism units, always within {@code [minPosition, maxPosition]}} */
    public double getPosition() {
        return position;
    }

    /**
     * Current modelled velocity in mechanism units/second (0 while stalled against an end-limit).
     * <b>Modelled internal state for tests/telemetry only</b> — a real mechanism's external encoder
     * reports position, not velocity, so season glue must not wire this as a hardware feedback sensor.
     */
    public double getVelocity() {
        return velocity;
    }

    /**
     * The same modelled velocity as {@link #getVelocity()}, expressed as a fraction of {@code maxSpeed} in
     * {@code [-1, 1]} — which is the unit a motor's back-EMF current model needs.
     *
     * <p><b>What this is for.</b> A {@code FakeMotor} driving a mechanism integrates its own unloaded speed
     * and knows nothing about the mechanism's end-stops or its load, so on its own it can never report the
     * current spike of a slide sitting on a hardstop. Feed this into
     * {@code FakeMotor.setMeasuredShaftSpeedFraction} once per tick and the motor's reported current
     * becomes the real thing: near zero while the mechanism runs freely, near stall while it is jammed.
     *
     * <p>Wiring it is the season glue's job, in the same place it already injects
     * {@link #getPosition()} into the mechanism's external-encoder fake — the plant does not reach into
     * the motor itself, because the plant is handed a plain {@code DcMotorSimple} and does not know that
     * the motor is a fake.
     *
     * @return the current speed as a fraction of {@code maxSpeed}, unitless, in {@code [-1, 1]}
     */
    public double getVelocityFractionOfMaxSpeed() {
        return velocity / maxSpeed;
    }

    /**
     * Teleport the state to a known start (e.g. a routine's initial pose), clamped to the end-limits, and
     * reset velocity to rest. For seeding only — the running command still comes through the motor.
     *
     * @param position where to place the mechanism, in its own position units; clamped to the end-limits,
     *                 and must be finite
     */
    public void setPosition(double position) {
        this.position = clampToLimits(requireFinite(position, "position"));
        this.velocity = 0.0;
    }

    private double clampToLimits(double value) {
        return Math.max(minPosition, Math.min(maxPosition, value));
    }

    // Fail loud on a non-finite value (fakehardware-plant §0): a NaN/Infinity would slip through the
    // Math.min/max clamp (IEEE propagates NaN) and poison state permanently — a caller bug, not a value
    // to fake-correct.
    private static double requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
        return value;
    }

    // As requireFinite, but an end limit may also be an infinity, which means "no hard stop on this side"
    // — the continuous-rotation case in the class javadoc. NaN is still a caller bug: it would slip the
    // Math.min/max clamp (IEEE propagates NaN) and poison position permanently.
    private static double requireFiniteOrInfinite(double value, String name) {
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException(name + " must be a number, got " + value);
        }
        return value;
    }
}
