package org.horizon36596.simloop.fakehardware;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorController;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.PIDCoefficients;
import com.qualcomm.robotcore.hardware.PIDFCoefficients;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;

/**
 * First-order drive-motor plant (fakehardware-plant §3), seeded from PsiKit's test {@code FakeMotor}.
 * Models commanded power -> speed with first-order lag, then integrates encoder position:
 * <pre>
 *   physicalPower = direction == REVERSE ? -power : power
 *   speed += (physicalPower - speed) * maxAccel * deltaTime
 *   if (|speed| &lt; eps &amp;&amp; |physicalPower| &lt; eps) speed = 0
 *   positionTicks += shaftSpeedFraction * maxVelocityInTicksPerSecond * deltaTime
 * </pre>
 * where {@code shaftSpeedFraction} is {@code speed} unless something outside the motor has taken
 * authority over the shaft -- see {@link #setMeasuredShaftSpeedFraction(double)}.
 *
 * {@code speed} is a unitless fraction of max wheel velocity in [-1, 1]; multiply by
 * {@link #getMaxVelocityTicksPerSecond()} for ticks/s. Pure plant: state changes only in
 * {@link #update(double)} (conventions §4).
 *
 * <h2>Two accessor pairs (BACKLOG B18)</h2>
 * {@code speed}/{@code positionTicks} track the motor's true PHYSICAL shaft motion -- direction is applied
 * once, on the way in, because a {@code REVERSE}-configured motor commanded {@code +power} really does spin
 * its shaft the other way. A real {@code DcMotorEx} then applies direction a SECOND time on the way back
 * out, so software always sees "positive power moves the encoder positive" regardless of which way the
 * motor happens to be bolted in. This class matches that with two differently-named accessor pairs:
 * <ul>
 *   <li><b>SDK-reported</b> — {@link #getCurrentPosition()}, {@link #getVelocityTicksPerSecond()} (and
 *       everything built on them: {@link #getVelocity()}, {@link #getVelocity(AngleUnit)}). Direction
 *       applied on read, matching real {@code DcMotorEx}. This is what season/robot code should read.</li>
 *   <li><b>Physical</b> — {@link #getPhysicalPositionTicks()}, {@link #getPhysicalVelocityTicksPerSecond()}.
 *       Direction already baked into how the shaft moved; NOT re-applied on read. This is what a plant that
 *       models the mechanism (e.g. {@code MecanumDrivePlant}, which combines it with a separate physical
 *       mounting-sign fact) needs to read, because it cares about which way the shaft actually turned, not
 *       about the software abstraction layered on top of it.</li>
 * </ul>
 *
 * <p><b>The two pairs normally describe the same shaft, and one case breaks that.</b> Ordinarily the only
 * difference between the pairs is the direction sign, so a reader may treat the physical pair as "the
 * unsigned version of" the reported pair. That stops being true the moment
 * {@link #setEncoderMeasuringAnotherMechanism(double, double)} is used: the reported pair then describes a
 * DIFFERENT mechanism while the physical pair still describes this motor's shaft. Anything generic that
 * walks a motor and dumps both — telemetry, a replay comparator — is reading two mechanisms, not one
 * number twice. See the next section.
 *
 * <h2>When the encoder on this port is not counting this motor's shaft</h2>
 * An FTC motor port carries a motor <i>and</i> an encoder input, and nothing makes them the same mechanism.
 * A team short of encoder ports plugs mechanism A's encoder into the port whose motor drives mechanism B,
 * and then reads A's position through B's motor handle. Robots really are wired this way, so the fake has
 * to be wirable that way too.
 *
 * <p>{@link #setEncoderMeasuringAnotherMechanism(double, double)} models that. It replaces what this port
 * REPORTS — {@link #getCurrentPosition()} and {@link #getVelocityTicksPerSecond()}, because on a real REV
 * port both are derived from the one encoder channel that is physically plugged in. Redirecting only the
 * position would hand a control loop a position from one shaft and a velocity from another.
 *
 * <p>It changes nothing else. This motor still integrates its own power, still models its own current, and
 * still reports its own shaft through {@link #getPhysicalPositionTicks()} and
 * {@link #getPhysicalVelocityTicksPerSecond()}. That is not a simplification, it is what the hardware does:
 * the motor whose port was borrowed really does keep spinning its own shaft while the number its port
 * reports belongs to somebody else.
 *
 * <h2>Current draw</h2>
 * {@link #getCurrent(CurrentUnit)} models a brushed DC motor's back-EMF relationship rather than returning
 * zero, because current is how robot code detects a hardstop or a jam. A motor with a mechanism on it must
 * be told how fast its shaft is really turning — see {@link #setMeasuredShaftSpeedFraction(double)}.
 */
public class FakeMotor extends AbstractFakeDevice implements DcMotorEx {

    private static final double EPS = 0.02;

    /** goBILDA 5203 312-RPM output CPR; used by the 2-arg ctor for tests that don't assert angular velocity. */
    private static final double DEFAULT_TICKS_PER_REV = 537.7;

    /**
     * Stall current at 12 V, amps, for the <b>stand-in</b> motor the short constructors assume: a goBILDA
     * 5203-series Yellow Jacket, off its published sheet. Every 5203 ratio shares one RS-555 motor, so the
     * figure does not change with the gearbox — which is why it makes a serviceable default for a fake that
     * nobody is asking about amps.
     *
     * <p><b>Any caller whose robot code reads current must pass its own datasheet numbers</b> through the
     * five-argument constructor rather than inheriting these. This is a default so that {@code getCurrent()}
     * has something honest to return, not a claim about what motor is on the other end. Same standing as
     * {@link #DEFAULT_TICKS_PER_REV}.
     */
    private static final double DEFAULT_STALL_CURRENT_AMPS = 9.2;

    /** No-load current at 12 V, amps, for the same stand-in motor and off the same sheet as above. */
    private static final double DEFAULT_FREE_CURRENT_AMPS = 0.25;

    private final double maxVelocityInTicksPerSecond;
    private final double maxAccel;
    private final double ticksPerRev;
    private final double stallCurrentAmps;
    private final double freeCurrentAmps;

    private double power = 0.0;
    private double positionTicks = 0.0;
    private double speed = 0.0;
    private Direction direction = Direction.FORWARD;
    private ZeroPowerBehavior zeroPowerBehavior = ZeroPowerBehavior.FLOAT;
    private double currentAlert = 0.0;
    private boolean motorEnabled = true;
    // The run mode the SDK would report. Stored rather than pretended, because a real controller's mode is
    // readable and two of the modes have effects a robot can observe: STOP_AND_RESET_ENCODER stops the shaft
    // and zeroes the count, and RUN_USING_ENCODER is what setVelocity switches to. See setMode.
    private RunMode mode = RunMode.RUN_WITHOUT_ENCODER;
    private int targetPositionTicks = 0;
    private int targetPositionToleranceTicks = 0;
    // How fast the shaft is REALLY turning, as a fraction of free speed. Unset means "nobody is holding this
    // motor back, so its own modelled speed is the truth" — the drivetrain case. A mechanism plant that
    // end-limits or jams the shaft takes authority by calling setMeasuredShaftSpeedFraction every tick.
    private double measuredShaftSpeedFraction = 0.0;
    private boolean measuredShaftSpeedFractionSet = false;
    // An encoder wired into this port that counts a DIFFERENT mechanism than this motor drives (see the
    // class javadoc). Unset means the ordinary case: the encoder on this port is this motor's own.
    private double anotherMechanismPositionTicks = 0.0;
    private double anotherMechanismVelocityTicksPerSecond = 0.0;
    private boolean anotherMechanismSet = false;

    /**
     * A motor with the default encoder resolution and the default current figures.
     *
     * @param maxVelocityInTicksPerSecond free speed, in encoder ticks/second; must be positive
     * @param maxAccel                    how fast speed chases commanded power, in reciprocal seconds
     */
    public FakeMotor(double maxVelocityInTicksPerSecond, double maxAccel) {
        this(maxVelocityInTicksPerSecond, maxAccel, DEFAULT_TICKS_PER_REV);
    }

    /**
     * A motor with a stated encoder resolution and the default current figures.
     *
     * @param maxVelocityInTicksPerSecond free speed, in encoder ticks/second; must be positive
     * @param maxAccel                    how fast speed chases commanded power, in reciprocal seconds
     * @param ticksPerRev encoder counts per output-shaft revolution — lets {@link #getVelocity(AngleUnit)}
     *                    return a real angular velocity (the harness passes the actual motor's CPR).
     */
    public FakeMotor(double maxVelocityInTicksPerSecond, double maxAccel, double ticksPerRev) {
        this(maxVelocityInTicksPerSecond, maxAccel, ticksPerRev,
                DEFAULT_STALL_CURRENT_AMPS, DEFAULT_FREE_CURRENT_AMPS);
    }

    /**
     * A motor with a stated encoder resolution and stated datasheet current figures.
     *
     * @param maxVelocityInTicksPerSecond free speed, in encoder ticks/second; must be positive
     * @param maxAccel                    how fast speed chases commanded power, in reciprocal seconds
     * @param ticksPerRev                 encoder counts per output-shaft revolution
     * @param stallCurrentAmps the motor's datasheet stall current at 12 V (&gt; 0) — the ceiling
     *                         {@link #getCurrent(CurrentUnit)} reports and the number a stall/jam threshold
     *                         is set below.
     * @param freeCurrentAmps  the motor's datasheet no-load current at 12 V (&ge; 0), what it draws spinning
     *                         against nothing but its own friction.
     */
    public FakeMotor(double maxVelocityInTicksPerSecond, double maxAccel, double ticksPerRev,
            double stallCurrentAmps, double freeCurrentAmps) {
        if (ticksPerRev <= 0) {
            throw new IllegalArgumentException("ticksPerRev must be positive, got " + ticksPerRev);
        }
        if (!(stallCurrentAmps > 0)) {
            throw new IllegalArgumentException("stallCurrentAmps must be positive, got " + stallCurrentAmps);
        }
        if (!(freeCurrentAmps >= 0) || freeCurrentAmps > stallCurrentAmps) {
            throw new IllegalArgumentException("freeCurrentAmps must be in [0, stallCurrentAmps], got "
                    + freeCurrentAmps);
        }
        this.maxVelocityInTicksPerSecond = maxVelocityInTicksPerSecond;
        this.maxAccel = maxAccel;
        this.ticksPerRev = ticksPerRev;
        this.stallCurrentAmps = stallCurrentAmps;
        this.freeCurrentAmps = freeCurrentAmps;
    }

    /**
     * Tell this motor how fast its shaft is REALLY turning, as a fraction of free speed in {@code [-1, 1]},
     * when something outside the motor decides that — a mechanism plant's end-stop, or a jam.
     *
     * <p><b>Why this exists.</b> A {@code FakeMotor} integrates its own unloaded speed and knows nothing
     * about the mechanism bolted to it, so a slide sitting on its bottom hardstop would still model a
     * happily spinning shaft and report near-zero current. Current is what a re-zero and a stall detector
     * read, so the motor has to be told. A drivetrain motor never calls this and keeps using its own
     * modelled speed.
     *
     * <p><b>Call it every tick once you start.</b> The value is used until it is replaced; it is not
     * consumed. The season glue calls this right beside the line that injects the plant's position into the
     * mechanism's external-encoder fake, so both halves of "what the plant knows" reach the hardware layer
     * in one place.
     *
     * @param fractionOfFreeSpeed the shaft's true speed over its free speed, clamped into {@code [-1, 1]};
     *                            e.g. {@code Mechanism1DofPlant.getVelocityFractionOfMaxSpeed()}. A
     *                            non-finite value is rejected rather than silently poisoning the current.
     */
    public void setMeasuredShaftSpeedFraction(double fractionOfFreeSpeed) {
        if (!Double.isFinite(fractionOfFreeSpeed)) {
            throw new IllegalArgumentException(
                    "measured shaft speed fraction must be finite, got " + fractionOfFreeSpeed);
        }
        this.measuredShaftSpeedFraction = Math.max(-1.0, Math.min(1.0, fractionOfFreeSpeed));
        this.measuredShaftSpeedFractionSet = true;
    }

    /**
     * Tell this port that the encoder plugged into it is counting a DIFFERENT mechanism than this motor
     * drives, and say what that encoder reads right now.
     *
     * <p><b>Why this exists.</b> See the class javadoc: an FTC motor port carries a motor and an encoder
     * input, and a team short of encoder ports wires mechanism A's encoder into the port whose motor drives
     * mechanism B, then reads A through B's handle. Without this the sim would report B's own shaft count to
     * code that is asking where A is, and A would read as frozen at zero while B ran.
     *
     * <p><b>Why position and velocity go in together.</b> A real REV port derives both from the one encoder
     * channel that is physically plugged into it. Redirecting only the position would give a control loop a
     * position from one shaft and a velocity from another, which is a robot that does not exist. Passing
     * both in one call also means there is no way to set one and forget the other.
     *
     * <p><b>What it does not change.</b> This motor goes on integrating its own power in
     * {@link #update(double)}, goes on modelling its own current, and goes on reporting its own shaft
     * through {@link #getPhysicalPositionTicks()} and {@link #getPhysicalVelocityTicksPerSecond()}. That is
     * the hardware being modelled, not a corner being cut: the motor whose port is borrowed really is still
     * turning.
     *
     * <p><b>Both numbers are PHYSICAL, and direction is still applied on read.</b> Pass what the encoder
     * actually counted; {@link #getCurrentPosition()} and {@link #getVelocityTicksPerSecond()} multiply by
     * this handle's direction sign the way a real {@code DcMotorEx} does (BACKLOG B18), so a {@code REVERSE}
     * handle on this port reports both negated, exactly as it would on the robot.
     *
     * <p><b>Call it every tick once you start.</b> The values are used until they are replaced; they are not
     * consumed on read. That matches {@link #setMeasuredShaftSpeedFraction(double)} and is deliberate: a
     * value that expired on read would make the answer depend on the order in which callers happened to
     * read it. The cost is that a tick where the glue forgets to call this reports last tick's numbers and
     * looks like a slow-moving mechanism, so set it in the same place that steps the plant owning that
     * encoder, unconditionally, every tick.
     *
     * @param physicalTicks          the encoder's count in ticks, before direction is applied
     * @param physicalTicksPerSecond how fast that count is changing, in ticks/second, before direction is
     *                               applied. This is the OTHER mechanism's rate — do not scale it by this
     *                               motor's free speed. A non-finite value in either argument is rejected
     *                               rather than silently turning every read on this port into nonsense.
     */
    public void setEncoderMeasuringAnotherMechanism(double physicalTicks, double physicalTicksPerSecond) {
        if (!Double.isFinite(physicalTicks)) {
            throw new IllegalArgumentException(
                    "another mechanism's encoder position must be finite, got " + physicalTicks);
        }
        if (!Double.isFinite(physicalTicksPerSecond)) {
            throw new IllegalArgumentException(
                    "another mechanism's encoder velocity must be finite, got " + physicalTicksPerSecond);
        }
        this.anotherMechanismPositionTicks = physicalTicks;
        this.anotherMechanismVelocityTicksPerSecond = physicalTicksPerSecond;
        this.anotherMechanismSet = true;
    }

    /**
     * Hand this port's reported position and velocity back to this motor's own encoder (the state a fresh
     * {@code FakeMotor} is in).
     *
     * <p>The count this motor kept while something else was being reported is still there and is what comes
     * back — it never stopped integrating, so clearing does not resume from where the other mechanism's
     * number left off.
     */
    public void clearEncoderMeasuringAnotherMechanism() {
        this.anotherMechanismPositionTicks = 0.0;
        this.anotherMechanismVelocityTicksPerSecond = 0.0;
        this.anotherMechanismSet = false;
    }

    /** {@return whether this port is reporting another mechanism's encoder rather than this motor's own shaft} */
    public boolean hasEncoderMeasuringAnotherMechanism() {
        return anotherMechanismSet;
    }

    /** Hand shaft-speed authority back to the motor's own model (the state a fresh {@code FakeMotor} is in). */
    public void clearMeasuredShaftSpeedFraction() {
        this.measuredShaftSpeedFraction = 0.0;
        this.measuredShaftSpeedFractionSet = false;
    }

    @Override
    public void update(double deltaTime) {
        // Direction is applied ONCE here, to compute the shaft's true PHYSICAL power: a REVERSE-configured
        // motor commanded +power really does spin its shaft the other way. It is NOT applied again when
        // getPhysicalPositionTicks()/getPhysicalVelocityTicksPerSecond() report this state -- direction is
        // re-applied only by the SDK-reported pair (getCurrentPosition(), getVelocityTicksPerSecond()), on
        // read, matching real DcMotorEx (BACKLOG B18; see class javadoc for the two-accessor split).
        // STOP_AND_RESET_ENCODER holds the shaft stopped and the count at zero for as long as it is the
        // mode: the real controller refuses to send a power command while in it, so nothing can start the
        // motor again until the mode changes. Returning early is how that reads most plainly.
        if (mode == RunMode.STOP_AND_RESET_ENCODER) {
            speed = 0.0;
            positionTicks = 0.0;
            // A plant's injected shaft speed is stale the moment the encoder is reset. This mode holds the
            // shaft still, so nothing outside can be measuring it turning; left set, it would make
            // getSpeed() and getCurrent() report a moving shaft on a motor that is stopped. A plant that
            // still has authority re-asserts it on its next tick, as it does every tick anyway.
            clearMeasuredShaftSpeedFraction();
            return;
        }

        double physicalPower = drivenPhysicalPower();
        speed += (physicalPower - speed) * maxAccel * deltaTime;
        if (Math.abs(speed) < EPS && Math.abs(physicalPower) < EPS) {
            speed = 0.0;
        }
        // The shaft's encoder counts what the shaft ACTUALLY did. When something outside the motor knows
        // that better than the motor's own model does (a mechanism plant on its hardstop, a jam), that
        // override is the truth for position, velocity and current alike -- a real motor encoder does not
        // keep counting while the shaft is held still.
        positionTicks += shaftSpeedFraction() * maxVelocityInTicksPerSecond * deltaTime;
    }

    /**
     * How fast the shaft is really turning, as a fraction of free speed in {@code [-1, 1]}: the value
     * {@link #setMeasuredShaftSpeedFraction(double)} injected if there is one, otherwise this motor's own
     * unloaded first-order model. Physical (direction already applied), see class javadoc.
     */
    private double shaftSpeedFraction() {
        return measuredShaftSpeedFractionSet ? measuredShaftSpeedFraction : speed;
    }

    /** {@return the unitless shaft speed in [-1, 1] (fraction of max velocity); physical, see class javadoc} */
    public double getSpeed() {
        return shaftSpeedFraction();
    }

    /**
     * SDK-reported wheel velocity in encoder ticks/second: direction applied on read, so a REVERSE motor
     * commanded positive power reports positive, matching real {@code DcMotorEx} (BACKLOG B18). Season code
     * should read this; a plant modelling the mechanism should read {@link #getPhysicalVelocityTicksPerSecond()}.
     *
     * <p>Reports the other mechanism's rate when
     * {@link #setEncoderMeasuringAnotherMechanism(double, double)} has been used, because a real port
     * derives velocity from the same encoder channel it derives position from.
     *
     * @return the velocity in encoder ticks/second, with this motor's direction applied
     */
    public double getVelocityTicksPerSecond() {
        return reportedSign() * countedVelocityTicksPerSecond();
    }

    /**
     * What the encoder on this port is counting per second, in physical ticks/second: the other mechanism's
     * rate if one has been declared, otherwise this motor's own shaft rate. Physical — direction is
     * applied by the reader, see class javadoc.
     */
    private double countedVelocityTicksPerSecond() {
        return anotherMechanismSet
                ? anotherMechanismVelocityTicksPerSecond
                : shaftSpeedFraction() * maxVelocityInTicksPerSecond;
    }

    /**
     * True modelled shaft velocity in encoder ticks/second, direction already baked in and NOT re-applied
     * (BACKLOG B18; see class javadoc). This is what a plant that models the mechanism reads.
     *
     * @return the true shaft velocity in encoder ticks/second, direction already baked in
     */
    public double getPhysicalVelocityTicksPerSecond() {
        return shaftSpeedFraction() * maxVelocityInTicksPerSecond;
    }

    /** {@return the free speed this motor was built with, in encoder ticks/second} */
    public double getMaxVelocityTicksPerSecond() {
        return maxVelocityInTicksPerSecond;
    }

    /** +1 for {@code FORWARD}, -1 for {@code REVERSE} -- the SDK's direction-to-report-sign rule (B18). */
    private double reportedSign() {
        return direction == Direction.REVERSE ? -1.0 : 1.0;
    }

    /**
     * The power actually reaching the windings right now, direction already applied: whatever
     * {@link #setPower(double)} commanded, unless something is stopping it getting there.
     *
     * <p>Two things stop it, and both leave {@link #getPower()} still reporting the commanded value, which
     * is exactly why this is a separate number. A disabled channel cannot drive at all - on the real REV
     * controller {@code setMotorDisable} sends {@code LynxSetMotorChannelEnableCommand(false)}, and only a
     * fresh power or velocity command turns it back on, which is why {@link #setPower(double)} and
     * {@link #setVelocity(double)} re-enable it here. {@link RunMode#STOP_AND_RESET_ENCODER} does the same
     * by refusing to send a power command while it is the mode.
     *
     * <p>One method rather than the same condition written twice, because {@link #update(double)} and
     * {@link #getCurrent(CurrentUnit)} have to agree: a shaft that is not being driven cannot also be
     * drawing stall current. Getting that wrong would have a deliberate encoder reset look like a jam to
     * any current-based stall detector.
     */
    private double drivenPhysicalPower() {
        if (!motorEnabled || mode == RunMode.STOP_AND_RESET_ENCODER) {
            return 0.0;
        }
        return reportedSign() * power;
    }

    // ---- DcMotorSimple ----
    @Override public Direction getDirection() { return direction; }
    @Override public void setDirection(Direction direction) { this.direction = direction; }
    @Override public double getPower() { return power; }
    /**
     * Commands power in {@code [-1, 1]}, and re-enables the motor channel unless the run mode is
     * {@link RunMode#STOP_AND_RESET_ENCODER}.
     *
     * <p>Both halves of that are {@code LynxDcMotorController.internalSetMotorPower}. It builds a command
     * for the current mode and then enables the channel <b>only if it built one</b>; the
     * {@code STOP_AND_RESET_ENCODER} branch is explicitly {@code command = null} with the comment "setting
     * motor power in this mode doesn't do anything". So on a real robot a power command is how a disabled
     * motor comes back — except while the encoder is being reset, where it is ignored outright and a
     * disabled channel stays disabled.
     */
    @Override public void setPower(double power) {
        this.power = Math.max(-1.0, Math.min(1.0, power));
        if (mode != RunMode.STOP_AND_RESET_ENCODER) {
            this.motorEnabled = true;
        }
    }

    // ---- DcMotor ----
    @Override public ZeroPowerBehavior getZeroPowerBehavior() { return zeroPowerBehavior; }
    @Override public void setZeroPowerBehavior(ZeroPowerBehavior behavior) { this.zeroPowerBehavior = behavior; }

    /**
     * SDK-reported encoder position in ticks: direction applied on read, so a REVERSE motor commanded
     * positive power reports positive, matching real {@code DcMotorEx} (BACKLOG B18). Season code should
     * read this; a plant modelling the mechanism should read {@link #getPhysicalPositionTicks()}.
     *
     * <p>Reports the value from {@link #setEncoderMeasuringAnotherMechanism(double, double)} when one has
     * been declared — the case where the encoder plugged into this port counts some other mechanism.
     * Direction is applied either way, because it is the handle that applies it, not the encoder.
     */
    @Override public int getCurrentPosition() { return (int) (reportedSign() * countedPositionTicks()); }

    /**
     * What the encoder on this port has counted, in physical ticks: the other mechanism's count if one has
     * been declared, otherwise this motor's own shaft count. Physical — direction is applied by the
     * reader, see class javadoc.
     */
    private double countedPositionTicks() {
        return anotherMechanismSet ? anotherMechanismPositionTicks : positionTicks;
    }

    /**
     * True modelled shaft position in encoder ticks, direction already baked in and NOT re-applied (BACKLOG
     * B18; see class javadoc). This is what a plant that models the mechanism reads.
     *
     * @return the true shaft position in encoder ticks, direction already baked in
     */
    public double getPhysicalPositionTicks() { return positionTicks; }

    // ---- Unsupported / no-op stubs: fail loud where a real read would be expected (conventions §4),
    //      no-op where the SDK interface only needs a stub for the sim path. ----
    @Override @Deprecated public void setPowerFloat() { /* no-op: use setPower(0) + FLOAT behavior */ }
    @Override public boolean getPowerFloat() { return false; }
    /**
     * Stores the RUN_TO_POSITION target, in encoder ticks, and does nothing else with it.
     *
     * <p>Nothing in this class ever drives toward it, because {@link #setMode} refuses
     * {@link RunMode#RUN_TO_POSITION}. The pair is kept rather than deleted because a real
     * {@code DcMotorImpl} accepts and reports the target in any mode, so subsystem code that sets a target
     * during configuration and only later chooses a mode behaves the same here as on the robot. Read it as
     * "recorded, never acted on" — if RUN_TO_POSITION is ever modelled, this is already the right storage.
     */
    @Override public void setTargetPosition(int position) { this.targetPositionTicks = position; }
    @Override public int getTargetPosition() { return targetPositionTicks; }

    /**
     * Always false, because {@link RunMode#RUN_TO_POSITION} is refused outright by {@link #setMode} — see
     * there. On a real controller this is false outside RUN_TO_POSITION too
     * ({@code LynxDcMotorController.isBusy}), so this fake agrees with the hardware everywhere the hardware
     * is reachable from here.
     */
    @Override public boolean isBusy() { return false; }

    /**
     * Sets the run mode, with the one effect a real controller has that a robot can see from the outside.
     *
     * <p>{@link RunMode#STOP_AND_RESET_ENCODER} stops the shaft and zeroes the count, which is what
     * {@code LynxDcMotorController.setMotorMode} does: it commands zero power and sends
     * {@code LynxResetMotorEncoderCommand}. Note it deliberately does <b>not</b> clear {@link #getPower()} —
     * the real controller saves the previous power and re-issues it, so the API-visible power survives a
     * reset even though the shaft stops. This fake reproduces that, rather than the more obvious and wrong
     * "zero everything".
     *
     * <p>{@link RunMode#RUN_TO_POSITION} throws. This plant is power-driven and has no closed position loop,
     * so accepting the mode would mean reporting {@code isBusy() == false} forever and never moving toward
     * the target — a robot would sit still in sim and drive on the field. The project's convention is an
     * external encoder plus an in-code loop rather than RUN_TO_POSITION, so nothing here needs it; failing
     * loudly is the honest answer for the fake rather than a plausible wrong one.
     *
     * @param mode the requested mode; the three deprecated modes are migrated first, as {@code DcMotorImpl}
     *             does via {@code RunMode.migrate()}
     * @throws UnsupportedOperationException if {@code mode} is {@link RunMode#RUN_TO_POSITION}
     */
    @Override public void setMode(RunMode mode) {
        RunMode migrated = mode.migrate();
        if (migrated == RunMode.RUN_TO_POSITION) {
            throw new UnsupportedOperationException("FakeMotor does not model RUN_TO_POSITION: this plant is "
                    + "power-driven and has no closed position loop, so the mode would report isBusy() == "
                    + "false forever and never move. Drive to a position with an external encoder and your "
                    + "own loop, which is what this robot does on real hardware.");
        }
        boolean theModeActuallyChanged = migrated != this.mode;
        this.mode = migrated;
        if (migrated == RunMode.STOP_AND_RESET_ENCODER) {
            this.speed = 0.0;
            this.positionTicks = 0.0;
        }
        if (theModeActuallyChanged) {
            // A real mode change re-enables the channel, and that is not obvious from the outside, so here
            // is the path through LynxDcMotorController.setMotorMode. The whole body is guarded by
            // "if the mode is not already this one", and every route out of it issues a power command that
            // ends in internalSetMotorEnable(true):
            //   - entering STOP_AND_RESET_ENCODER calls internalSetMotorPower(motor, 0) BEFORE recording the
            //     new mode, so that zero-power command is built in the OUTGOING mode and is therefore
            //     non-null, and enables;
            //   - every other mode sends a channel-mode command and then re-issues the saved previous power,
            //     which is likewise a non-null command, and enables.
            // So "the channel came back on" is a consequence of changing mode at all, not of which mode was
            // chosen. Note this is the opposite of setPower above: a power command while ALREADY in
            // STOP_AND_RESET_ENCODER builds no command and enables nothing.
            // (2026-09-17 charter review, SDK-fidelity angle.)
            this.motorEnabled = true;
        }
    }

    @Override public RunMode getMode() { return mode; }
    @Override public MotorConfigurationType getMotorType() {
        // Fidelity (plant §0): SolversLib Motor.getCPR() reads getMotorType().getTicksPerRev() and the
        // Motor ctor reads getAchieveableMaxTicksPerSecond() — an empty MotorConfigurationType returns 0
        // for both, breaking CPR-derived reads. Populate a coherent type: ticksPerRev is the real CPR, and
        // maxRPM/fraction are chosen so getAchieveableMaxTicksPerSecond() == this plant's maxVelocityInTicksPerSecond
        // (= ticksPerRev * maxRPM * fraction / 60). A fresh instance per call (like nothing shared to mutate);
        // SolversLib clones it before setting fields, so callers never corrupt this fake's state.
        MotorConfigurationType type = new MotorConfigurationType();
        type.setTicksPerRev(ticksPerRev);
        type.setMaxRPM(maxVelocityInTicksPerSecond / ticksPerRev * 60.0);
        type.setAchieveableMaxRPMFraction(1.0);
        return type;
    }
    @Override public void setMotorType(MotorConfigurationType motorType) { /* no-op */ }
    @Override public int getPortNumber() { return 0; }

    @Override
    public DcMotorController getController() {
        throw new UnsupportedOperationException(
                "FakeMotor has no DcMotorController in sim — read state via getCurrentPosition()/getSpeed()");
    }

    // ---- DcMotorEx ----
    // The kinematic model is power-driven; velocity READS work (encoder velocity via MotorEx), but
    // closed-loop velocity/PIDF control and current draw are not modelled (no-op / zero).
    @Override public double getVelocity() { return getVelocityTicksPerSecond(); }
    @Override public double getVelocity(AngleUnit unit) {
        // Mirror the SDK: ticks/s -> rad/s (via CPR), then convert with the UNNORMALIZED unit — this is a
        // rate, not an angle, so it must NOT be wrapped to [-180,180]/[-pi,pi] (what fromDegrees would do).
        // The CPR here is THIS port's configured motor type even when the encoder plugged into it belongs to
        // another mechanism with a different CPR. That is not a corner cut: a real Control Hub converts with
        // the motor type configured on the port, so an angle read off a borrowed port is wrong on the robot
        // in exactly the same way.
        double radiansPerSecond = getVelocityTicksPerSecond() / ticksPerRev * 2.0 * Math.PI;
        return unit.getUnnormalized().fromRadians(radiansPerSecond);
    }
    /**
     * Commands a shaft velocity in encoder ticks per second, reproducing what
     * {@code LynxDcMotorController.setMotorVelocity} does around the command: it switches the run mode to
     * {@link RunMode#RUN_USING_ENCODER} unless the motor is already in a mode that accepts a velocity, and it
     * enables the motor channel. Both used to be missing here, along with the command itself — the method
     * accepted the call and did nothing, so velocity-controlled robot code stood still in sim while the real
     * motor spun.
     *
     * <p>The command is expressed as the equivalent fraction of free speed and then runs through the same
     * first-order model as {@link #setPower(double)}, so the shaft approaches the requested velocity rather
     * than jumping to it. That is the plant this class has (R6: parameterized first-order dynamics), not a
     * shortcut: a real motor under closed-loop velocity control also takes time to get there. A request
     * beyond {@code maxVelocityInTicksPerSecond} saturates, exactly as the hardware does.
     *
     * @param angularRate target shaft velocity in encoder ticks per second, in the motor's logical
     *                    direction — {@link #setDirection} is applied on top, as it is for power
     */
    @Override public void setVelocity(double angularRate) {
        if (mode != RunMode.RUN_USING_ENCODER && mode != RunMode.RUN_TO_POSITION) {
            this.mode = RunMode.RUN_USING_ENCODER;
        }
        this.power = Math.max(-1.0, Math.min(1.0, angularRate / maxVelocityInTicksPerSecond));
        this.motorEnabled = true;
    }

    /**
     * Commands a shaft velocity given as an angular rate, converted through this port's configured CPR
     * exactly as {@code LynxDcMotorController.setMotorVelocity(int, double, AngleUnit)} converts it, then
     * handed to {@link #setVelocity(double)}.
     *
     * @param angularRate target angular velocity of the output shaft, in {@code unit} per second
     * @param unit        the angle unit {@code angularRate} is expressed in
     */
    @Override public void setVelocity(double angularRate, AngleUnit unit) {
        double revolutionsPerSecond = unit.getUnnormalized().toRadians(angularRate) / (2.0 * Math.PI);
        setVelocity(revolutionsPerSecond * ticksPerRev);
    }
    @Override public void setMotorEnable() { motorEnabled = true; }
    @Override public void setMotorDisable() { motorEnabled = false; }
    @Override public boolean isMotorEnabled() { return motorEnabled; }
    @Override public void setPIDCoefficients(RunMode mode, PIDCoefficients pidCoefficients) { /* no closed loop */ }
    @Override public void setPIDFCoefficients(RunMode mode, PIDFCoefficients pidfCoefficients) { /* no-op */ }
    @Override public void setVelocityPIDFCoefficients(double p, double i, double d, double f) { /* no-op */ }
    @Override public void setPositionPIDFCoefficients(double p) { /* no-op */ }
    @Override public PIDCoefficients getPIDCoefficients(RunMode mode) { return new PIDCoefficients(0, 0, 0); }
    @Override public PIDFCoefficients getPIDFCoefficients(RunMode mode) { return new PIDFCoefficients(0, 0, 0, 0); }
    @Override public void setTargetPositionTolerance(int tolerance) { this.targetPositionToleranceTicks = tolerance; }
    @Override public int getTargetPositionTolerance() { return targetPositionToleranceTicks; }
    /**
     * Modelled current draw. A brushed DC motor's winding current follows the voltage across it minus its
     * own back-EMF, and in this plant's normalized units both of those are already fractions of full scale:
     * <pre>
     *   torqueCurrent   = stallCurrent * |commandedPhysicalPower - shaftSpeedFraction|
     *   frictionCurrent = freeCurrent  * |shaftSpeedFraction|
     *   current         = min(stallCurrent, torqueCurrent + frictionCurrent)
     * </pre>
     * So a motor commanded full power from rest draws stall current; one running freely at the speed it was
     * asked for draws only its no-load current; and one commanded 0.3 while something holds its shaft still
     * draws 30% of stall. That last case is the one this exists for — a slide driven into its hardstop, or a
     * jammed mechanism.
     *
     * <p>{@code shaftSpeedFraction} is this motor's own modelled {@link #getSpeed()} unless a mechanism
     * plant has taken authority via {@link #setMeasuredShaftSpeedFraction(double)} — a motor with a
     * mechanism on it cannot know it is being held back, so it has to be told.
     *
     * <p><b>Not modelled:</b> battery sag, winding heat, and the inrush spike of a direction reversal. This
     * is a steady-state figure good enough to set a stall threshold against, not an electrical simulation
     * (domain R6). Pure read — no state changes here, so replay stays bit-stable (domain R5).
     */
    @Override public double getCurrent(CurrentUnit unit) {
        double shaftSpeedFraction = shaftSpeedFraction();
        double physicalPower = drivenPhysicalPower();
        double torqueCurrent = stallCurrentAmps * Math.abs(physicalPower - shaftSpeedFraction);
        double frictionCurrent = freeCurrentAmps * Math.abs(shaftSpeedFraction);
        double amps = Math.min(stallCurrentAmps, torqueCurrent + frictionCurrent);
        return unit.convert(amps, CurrentUnit.AMPS);
    }
    @Override public double getCurrentAlert(CurrentUnit unit) {
        return unit.convert(currentAlert, CurrentUnit.AMPS);
    }
    @Override public void setCurrentAlert(double current, CurrentUnit unit) {
        this.currentAlert = CurrentUnit.AMPS.convert(current, unit);
    }
    /** True once the modelled current reaches the alert threshold, matching the SDK's own over-current flag. */
    @Override public boolean isOverCurrent() {
        // A zero/unset alert means "no threshold configured", which the SDK reports as never over-current
        // rather than as always over-current.
        return currentAlert > 0.0 && getCurrent(CurrentUnit.AMPS) >= currentAlert;
    }
}
