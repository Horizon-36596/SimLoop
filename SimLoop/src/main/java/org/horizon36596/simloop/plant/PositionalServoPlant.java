package org.horizon36596.simloop.plant;

import org.horizon36596.simloop.config.PositionalServoSimConfig;
import org.horizon36596.simloop.fakehardware.FakeServo;

/**
 * 1-DOF mechanism plant for a joint driven by a <b>positional servo</b> — the servo-side twin of
 * {@link Mechanism1DofPlant} (fakehardware-plant §4, domain R6). Where that plant is <b>power-driven</b>
 * ({@code setPower}, integrate a velocity), this one is <b>command-driven</b>: the servo is told a
 * position and the mechanism takes time to arrive.
 *
 * <p><b>Why this class has to exist at all.</b> {@link FakeServo} reports a commanded position the instant
 * it is set, and that is <em>correct</em> — the SDK's own {@code ServoImpl} does the same, and the fakes
 * must match real device semantics exactly. But the arm bolted to that servo has not moved yet. Drawing
 * straight off {@code getScaledPosition()} makes the picture teleport, which is drawing a lie. This plant
 * is the piece that separates "what the servo was told" from "where the mechanism is", so a visualizer
 * (and any sim consumer) can show the second one.
 *
 * <p><b>Nothing external can push this joint.</b> Position is a function of the command and the joint's
 * own history, and there is no load term: no gravity, no gear lash, and no external torque. A turret on a
 * chassis that is accelerating and rotating is really being pushed by that chassis, and this plant will
 * not show it — what it does show is the servo's own lag, its slew ceiling and its deadband fighting a
 * target that keeps moving, which is a real and sufficient share of the problem. Anything that has to
 * claim a joint <i>held against a load</i> needs a plant that models the load; {@code docs/BACKLOG.md} B27
 * tracks the first piece of that (a gravity-loaded servo arm does not sag).
 *
 * <p><b>There is no feedback here, and that is the point.</b> A bare servo has no encoder, so nothing on
 * the robot can read this position — it exists only in sim, for drawing and for telemetry a human looks
 * at. Season code must not wire it back as if it were a sensor; if the real joint has an absolute encoder,
 * that is a separate fake device.
 *
 * <p><b>Model.</b> Round the command to what the controller can actually generate, ignore it entirely if
 * the horn is already close enough, then approach first-order, capped by a travel rate and stopped by hard
 * end limits:
 * <pre>
 *   command  = round(servo.getScaledPosition() / quantum) * quantum   // command resolution
 *   target   = clamp(positionAtCommandZero + command*(positionAtCommandOne - positionAtCommandZero))
 *   held     = (position - positionAtCommandZero) / (positionAtCommandOne - positionAtCommandZero)
 *   if (|command - held| &lt;= deadband) { velocity = 0; return; }    // the servo does not move at all
 *   step     = (target - position) * (1 - exp(-dt/tau))     // exact first-order approach
 *   step     = clamp(step, -maxSpeed*dt, +maxSpeed*dt)      // travel-rate ceiling (servo slew limit)
 *   position = clamp(position + step, minPosition, maxPosition)
 * </pre>
 * A small move is dominated by the exponential (dt-independent, so replay is stable at any tick rate); a
 * large one is dominated by the rate cap, which is what a real servo does — it slews at roughly a fixed
 * degrees-per-second and then eases in.
 *
 * <p><b>That dt-independence is only true while neither the rate cap nor the deadband is active.</b> Both
 * are evaluated once per tick, so a coarse tick can step across the edge of the deadband where a fine one
 * would have stopped at it, and the joint comes to rest in a slightly different place. Replay of a
 * recorded run is unaffected, because a replay uses the same ticks; re-running the same scenario at a
 * different tick rate and expecting the same resting position is what does not hold. No velocity state is carried, because a positional servo has no
 * momentum worth modelling: cut the command mid-travel and it stops, it does not coast. This is
 * parameterized first-order dynamics, not rigid-body/contact physics (domain R6); {@code timeConstant},
 * {@code maxSpeed}, {@code commandDeadband} and {@code commandQuantum} are the Phase-4 calibration surface.
 *
 * <p><b>Why the deadband and the quantum are in the model and not left out as detail.</b> Both default to
 * zero, and a joint that only has to get roughly somewhere can leave them there. Anything that has to
 * <em>hold an aim</em> cannot. A servo with no deadband converges on its target every time, so a control
 * law above it looks like it works; a real servo stops as soon as it is within its deadband and stays
 * there, with a steady-state error that no amount of integral action removes, whose size and sign depend
 * on which way the joint arrived. The quantum is the separate floor underneath that: the achievable
 * positions are a grid, so even a perfect servo cannot be asked for a point between two of them. Leaving
 * either out does not make the sim slightly optimistic, it deletes the problem being studied.
 *
 * <p>Season-agnostic core: depends only on {@link FakeServo} + {@link PositionalServoSimConfig} (domain
 * R7, SimLoop rule 2). Deterministic: state changes only in {@link #update(double)} off the injected tick
 * {@code deltaTime}, never a wall-clock, and through {@link StrictMath#exp} so replay is bit-stable across
 * JVMs (domain R5); getters are pure reads (conventions §4).
 *
 * <p>It is one of the {@link OneDofPlant} implementations, so a subsystem with several degrees of freedom
 * can hold one of these beside a {@link Mechanism1DofPlant} inside a {@link MultiDofMechanismPlant} and
 * tick both from one call (BACKLOG B13).
 */
public final class PositionalServoPlant implements OneDofPlant {

    private final FakeServo servo;
    private final double timeConstant;
    private final double maxSpeed;
    private final double minPosition;
    private final double maxPosition;
    private final double positionAtCommandZero;
    private final double positionAtCommandOne;
    private final double commandDeadband;
    private final double commandQuantum;

    private double position;
    private double velocity;

    /**
     * Builds the plant for one servo-driven joint, parked at the position its current command maps to.
     *
     * @param servo  the servo the mechanism's subsystem drives via {@code setPosition} — the SAME
     *               {@link FakeServo} instance registered in the {@code FakeHardwareMap} the robot code
     *               resolves (domain R1), so unchanged code commands it in sim.
     * @param config the joint's first-order params, end stops and command→position map (glue-injected,
     *               domain R7). The plant starts <b>already at</b> the servo's current command, clamped —
     *               a servo powers up holding whatever it was last told, and starting anywhere else would
     *               fabricate a sweep at t=0 that the real joint never performs.
     * @throws IllegalArgumentException if {@code servo} is null, any config value is non-finite,
     *                                  {@code timeConstant <= 0}, {@code maxSpeed <= 0},
     *                                  {@code minPosition >= maxPosition}, the two command endpoints
     *                                  are the same position, or {@code commandDeadband} /
     *                                  {@code commandQuantum} is negative or {@code >= 1} (a deadband or a
     *                                  step as wide as the servo's whole sweep is a joint that can only
     *                                  ever sit at one place).
     */
    public PositionalServoPlant(FakeServo servo, PositionalServoSimConfig config) {
        if (servo == null) {
            throw new IllegalArgumentException("positional servo plant needs a servo to read commands from");
        }
        // Finiteness first, for the same reason as Mechanism1DofPlant: a NaN slips every comparison below
        // (NaN comparisons are always false) and then poisons position permanently through the clamp.
        double tau = requireFinite(config.timeConstant(), "timeConstant");
        double vMax = requireFinite(config.maxSpeed(), "maxSpeed");
        double min = requireFinite(config.minPosition(), "minPosition");
        double max = requireFinite(config.maxPosition(), "maxPosition");
        double atZero = requireFinite(config.positionAtCommandZero(), "positionAtCommandZero");
        double atOne = requireFinite(config.positionAtCommandOne(), "positionAtCommandOne");
        double deadband = requireFinite(config.commandDeadband(), "commandDeadband");
        double quantum = requireFinite(config.commandQuantum(), "commandQuantum");
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
        if (deadband < 0.0 || deadband >= 1.0) {
            throw new IllegalArgumentException(
                    "commandDeadband is in command units of the servo's [0, 1] sweep, so it must be in"
                            + " [0, 1), got " + deadband);
        }
        if (quantum < 0.0 || quantum >= 1.0) {
            throw new IllegalArgumentException(
                    "commandQuantum is in command units of the servo's [0, 1] sweep, so it must be in"
                            + " [0, 1), got " + quantum);
        }
        if (atZero == atOne) {
            throw new IllegalArgumentException(
                    "positionAtCommandZero and positionAtCommandOne must differ, both got " + atZero
                            + " — a servo whose sweep maps to one position is a joint that cannot move");
        }
        this.servo = servo;
        this.timeConstant = tau;
        this.maxSpeed = vMax;
        this.minPosition = min;
        this.maxPosition = max;
        this.positionAtCommandZero = atZero;
        this.positionAtCommandOne = atOne;
        this.commandDeadband = deadband;
        this.commandQuantum = quantum;
        this.position = commandedPosition();
        this.velocity = 0.0;
    }

    /** Advance the joint one tick toward the servo's current commanded position. */
    public void update(double deltaTime) {
        // A tick advances the clock forward by a finite amount; anything else is not a real sim tick
        // (FakeTimer is monotonic, domain R5), so treat it as no time passing rather than letting a NaN or
        // a backward step through the end-stop invariant.
        if (!Double.isFinite(deltaTime) || deltaTime <= 0.0) {
            return;
        }

        double command = quantizedCommand();
        double target = positionForCommand(command);

        // The deadband, and it is a hard "do nothing" rather than a small step. A real servo compares the
        // commanded pulse against its own internal pot and does not drive the motor at all while the two
        // are within its deadband, which is why a servo joint parks with a steady-state error that never
        // integrates away. Compared in command units because that is the space the servo's own comparator
        // works in, and because it is the space a characterization routine measures it in.
        if (Math.abs(command - commandHeldAt(position)) <= commandDeadband) {
            velocity = 0.0;
            return;
        }

        // Exact first-order approach toward the target (deltaTime-independent, StrictMath for bit-stable
        // cross-JVM replay).
        double step = (target - position) * (1.0 - StrictMath.exp(-deltaTime / timeConstant));

        // Travel-rate ceiling: a real servo slews at a bounded speed, so a big commanded jump ramps rather
        // than covering most of the distance in the first tick.
        double maxStep = maxSpeed * deltaTime;
        if (step > maxStep) {
            step = maxStep;
        } else if (step < -maxStep) {
            step = -maxStep;
        }

        double previousPosition = position;
        position = clampToLimits(position + step);
        // Velocity is derived from the move that actually happened, so a joint parked against an end stop
        // reports zero even while the servo is still commanding past it.
        velocity = (position - previousPosition) / deltaTime;
    }

    /** {@return the current mechanism position in mechanism units, always within {@code [minPosition, maxPosition]}} */
    public double getPosition() {
        return position;
    }

    /**
     * The position the servo is currently <b>commanding</b>, in mechanism units, clamped to the end stops.
     * The gap between this and {@link #getPosition()} is exactly the lag this class exists to model — a
     * useful thing to log next to the drawing.
     *
     * <p>The value is a mechanism-unit position, and it is derived from the command <b>after</b> rounding
     * to {@code commandQuantum}, because that is what the servo is really acting on. Deriving it from the
     * raw request instead would show a target the hardware was never asked for, and the residual error
     * against it would look like a control problem rather than the command resolution it is.
     *
     * @return the position the servo is actually aiming at, in the mechanism's own position units
     */
    public double getCommandedPosition() {
        return commandedPosition();
    }

    /**
     * Modelled speed over the last tick in mechanism units/second (0 while parked against an end stop or
     * once the joint has arrived). <b>Modelled internal state for tests/telemetry only</b> — a bare servo
     * has no encoder, so season glue must never wire this as hardware feedback.
     */
    public double getVelocity() {
        return velocity;
    }

    /**
     * Teleport the joint to a known start (e.g. the pose a routine begins in), clamped to the end stops.
     * For seeding only — the running command still comes through the servo.
     *
     * @param position where to place the joint, in the mechanism's own position units; clamped to the
     *                 end stops, and must be finite
     */
    public void setPosition(double position) {
        this.position = clampToLimits(requireFinite(position, "position"));
        this.velocity = 0.0;
    }

    /**
     * Map the servo's scaled (physical) command in {@code [0, 1]} onto mechanism units, clamped to the end
     * stops.
     *
     * <p><b>It reads {@code getScaledPosition()}, and swapping that for {@code getPosition()} is a real
     * bug, not a simplification.</b> Both return a value in {@code [0, 1]} ({@code scaleRange} is
     * constrained to that interval), but they mean different things: {@code getScaledPosition()} is where
     * the horn physically sits, while {@code getPosition()} undoes the scaling to hand the caller its own
     * request back. Read the second one and a servo narrowed to {@code scaleRange(0.2, 0.6)} would drive
     * the mechanism across its whole sweep instead of the 60% it can actually reach. Pinned by
     * {@code PositionalServoPlantTest.scaleRangeNarrowsThePhysicalSweep}.
     */
    private double commandedPosition() {
        return positionForCommand(quantizedCommand());
    }

    /**
     * The servo's scaled command rounded to the nearest multiple of {@code commandQuantum} — the pulse
     * the controller can actually generate. A quantum of {@code 0.0} means no rounding, which is the
     * default and leaves every existing config behaving exactly as it did.
     *
     * <p>Rounded, not truncated: a controller picks the nearest representable pulse to what it was asked
     * for. Truncating would bias every command in one direction, which reads as a calibration offset and
     * would send someone hunting for a mounting error that is not there.
     *
     * <p>The rounded value is clamped back into {@code [0, 1]}, because rounding to the nearest step can
     * land outside the sweep: a command of 1.0 with a quantum of 0.4 rounds to 1.2, and there is no such
     * pulse. A controller cannot generate a pulse the servo does not have, so neither can this.
     */
    private double quantizedCommand() {
        double command = servo.getScaledPosition();
        if (commandQuantum <= 0.0) {
            return command;
        }
        double rounded = Math.round(command / commandQuantum) * commandQuantum;
        return Math.max(0.0, Math.min(1.0, rounded));
    }

    /** Map a command in the servo's {@code [0, 1]} sweep onto mechanism units, clamped to the end stops. */
    private double positionForCommand(double command) {
        return clampToLimits(
                positionAtCommandZero + command * (positionAtCommandOne - positionAtCommandZero));
    }

    /**
     * The command that corresponds to a mechanism position — {@link #positionForCommand(double)} run
     * backwards, so the deadband can be compared in the servo's own command units. The two endpoints are
     * guaranteed to differ, so the division is safe.
     */
    private double commandHeldAt(double mechanismPosition) {
        return (mechanismPosition - positionAtCommandZero)
                / (positionAtCommandOne - positionAtCommandZero);
    }

    private double clampToLimits(double value) {
        return Math.max(minPosition, Math.min(maxPosition, value));
    }

    // Fail loud on a non-finite value (fakehardware-plant §0): a NaN/Infinity would slip through the
    // Math.min/max clamp (IEEE propagates NaN) and poison state permanently — a caller bug, not a value to
    // fake-correct.
    private static double requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
        return value;
    }
}
