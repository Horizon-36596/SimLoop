package org.horizon36596.simloop.fakehardware;

import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.ServoController;
import com.qualcomm.robotcore.util.Range;

/**
 * Fake {@link CRServo}: a continuous-rotation servo, the actuator V0's deposit ejects a line of pollen
 * with ({@code docs/specs/deposit.md} §2). A CR servo is commanded a <b>speed</b>, not a position — it
 * has no travel limit and no feedback of any kind — so unchanged robot code drives it with
 * {@code setPower} exactly as it drives a motor, and a timed eject is the only kind of eject there is.
 *
 * <h2>This fake stores what the hardware stores, which is not what the caller passed</h2>
 * The SDK's {@code CRServoImpl} does not keep the commanded power. It applies the direction, clips to
 * {@code [-1, 1]}, rescales the result into a servo position in {@code [0, 1]}, and sends <b>that</b> to
 * the controller; {@code getPower()} then reads the position back and undoes both steps. This fake does
 * the same arithmetic in the same order, because two behaviours fall out of it that a "just remember the
 * power" fake would get wrong:
 *
 * <ul>
 *   <li><b>{@code getPower()} reports the caller's own request back, not the direction-applied power.</b>
 *       The reversal is applied on the way in and undone on the way out. So a {@code REVERSE} servo told
 *       {@code 0.5} reports {@code 0.5}, while the horn really turns the other way — the same split
 *       {@code FakeMotor} documents for encoders (BACKLOG B18). {@link #getPhysicalPower()} is the other
 *       half: what the servo is really doing.</li>
 *   <li><b>Changing direction after a {@code setPower} does not change the motion.</b> The stored servo
 *       position is untouched, so the horn keeps turning the way it was; only the next {@code setPower}
 *       feels the new direction. That is what real hardware does — {@code setDirection} sends nothing to
 *       the servo — and it is what a naive fake gets backwards.</li>
 * </ul>
 *
 * <p><b>The round trip is not exact, and that is faithful.</b> A power stored as a servo position and
 * read back does not always return the identical double, because the two linear maps do not invert
 * exactly in binary floating point. This class calls the SDK's own {@link Range#clip(double, double,
 * double)} and {@link Range#scale(double, double, double, double, double)} rather than writing the
 * arithmetic out, <b>so the rounding error here is the same rounding error the real {@code CRServoImpl}
 * has, bit for bit</b> — an equivalent formula written by hand would round differently in the last place
 * and would be a fake that quietly disagrees with the hardware. It is deterministic either way, so replay
 * is unaffected (domain R5); tests compare with a tolerance.
 *
 * <h2>Where the motion is modelled</h2>
 * Nothing here turns power into travel — this class is the device surface only, exactly as
 * {@link FakeServo} is. Because the SDK's {@code CRServo} extends {@code DcMotorSimple}, a
 * {@code Mechanism1DofPlant} takes one of these directly, with no adapter and no separate plant class:
 * <pre>
 *   FakeCRServo ejector = new FakeCRServo();
 *   Mechanism1DofPlant ejectorPlant = new Mechanism1DofPlant(ejector, ejectorConfig);
 * </pre>
 * where {@code ejectorConfig} is a {@code ContinuousRotationSimConfig} — the first-order model with its
 * end-limit clamp switched off, so travel accumulates without bound. The plant advances on the injected
 * tick from {@code FakeTimer}, never a wall clock (domain R5, B13 Contract clause 3).
 *
 * <h2>Powering up stopped</h2>
 * A fresh instance holds the servo position that means <b>zero power</b>, so it does not turn until
 * something tells it to. On real hardware neutral is a trimmed pulse width that a mis-trimmed servo
 * creeps away from ({@code docs/specs/deposit.md} §2, open question 5); this fake's neutral is exact,
 * which is a thing the sim cannot warn anyone about and a bench test has to.
 */
public class FakeCRServo extends AbstractFakeDevice implements CRServo {

    /** Creates a servo sitting at neutral, so it does not turn until something commands it. */
    public FakeCRServo() {
    }

    // The SDK's own conversion bounds, named as CRServoImpl names them so the two can be read line for
    // line. CRServoImpl keeps them protected, so they are restated here rather than inherited.
    private static final double API_POWER_MIN = -1.0;
    private static final double API_POWER_MAX = 1.0;
    private static final double API_SERVO_POSITION_MIN = 0.0;
    private static final double API_SERVO_POSITION_MAX = 1.0;

    /** The servo position that means "stopped": power 0.0 rescaled into {@code [0, 1]}. */
    private static final double STOPPED_SERVO_POSITION = 0.5;

    /**
     * What the controller holds — a servo position in {@code [0, 1]}, exactly as {@code CRServoImpl}
     * sends it. NOT the commanded power: see the class javadoc.
     */
    private double servoPosition = STOPPED_SERVO_POSITION;

    private Direction direction = Direction.FORWARD;

    @Override
    public void update(double deltaTime) {
        // The device itself has no dynamics; a commanded speed is sent the instant it is set. The travel
        // that results is modelled by the Mechanism1DofPlant this servo drives.
    }

    // ---- DcMotorSimple ----

    /**
     * Command a speed in {@code [-1, 1]}: negative is one way, positive the other, {@code 0.0} is
     * stopped. Mirrors {@code CRServoImpl.setPower} step for step — reverse if the direction says so,
     * clip, then rescale into the servo position the controller actually receives.
     *
     * @param power the commanded speed. Values outside {@code [-1, 1]} are clipped, as the SDK clips
     *              them. A {@code NaN} power is <b>not</b> rejected here, because the real device does not
     *              reject it either: it propagates through the clip and the rescale and comes back out of
     *              {@link #getPower()} as {@code NaN}, which is a caller bug the plant reading this servo
     *              fail-safes to zero force. A fake that threw where the hardware does not would hide the
     *              bug in sim and let it through on the robot.
     */
    @Override
    public void setPower(double power) {
        double directedPower = (direction == Direction.REVERSE) ? -power : power;
        double clipped = Range.clip(directedPower, API_POWER_MIN, API_POWER_MAX);
        this.servoPosition = Range.scale(clipped,
                API_POWER_MIN, API_POWER_MAX, API_SERVO_POSITION_MIN, API_SERVO_POSITION_MAX);
    }

    /**
     * The commanded speed the caller asked for, clipped to {@code [-1, 1]} — <b>not</b> the direction the
     * horn actually turns. The direction reversal applied by {@link #setPower(double)} is undone here,
     * matching {@code CRServoImpl.getPower()}, so software reads back what it wrote regardless of how the
     * servo is configured. A plant modelling the mechanism wants {@link #getPhysicalPower()}, or reads
     * this together with {@link #getDirection()} as {@code Mechanism1DofPlant} does.
     */
    @Override
    public double getPower() {
        double power = Range.scale(servoPosition,
                API_SERVO_POSITION_MIN, API_SERVO_POSITION_MAX, API_POWER_MIN, API_POWER_MAX);
        return (direction == Direction.REVERSE) ? -power : power;
    }

    /**
     * The speed the horn is really turning at, in {@code [-1, 1]}: the direction is already baked in and
     * is not applied again. This is the sim-side truth, the counterpart of
     * {@code FakeServo.getScaledPosition()} and {@code FakeMotor.getPhysicalPositionTicks()} (BACKLOG
     * B18). <b>Sim-only — no such method exists on a real {@code CRServo}.</b>
     *
     * @return the horn's real turn rate, unitless, in {@code [-1, 1]}
     */
    public double getPhysicalPower() {
        return Range.scale(servoPosition,
                API_SERVO_POSITION_MIN, API_SERVO_POSITION_MAX, API_POWER_MIN, API_POWER_MAX);
    }

    /**
     * Which way a positive power turns the horn. Changing it does <b>not</b> change what the servo is
     * currently doing — see the class javadoc; only the next {@link #setPower(double)} is affected.
     */
    @Override
    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    @Override
    public Direction getDirection() {
        return direction;
    }

    // ---- CRServo ----

    /** {@return the servo port number; always {@code 0}, since a fake is not wired to a hub} */
    @Override
    public int getPortNumber() {
        return 0;
    }

    @Override
    public ServoController getController() {
        throw new UnsupportedOperationException(
                "FakeCRServo has no ServoController in sim — read state via getPower()/getPhysicalPower()");
    }

    // ---- HardwareDevice: matched to CRServoImpl rather than to AbstractFakeDevice's defaults ----

    /** {@code 1}, as {@code CRServoImpl} reports. */
    @Override
    public int getVersion() {
        return 1;
    }

    /**
     * Resets the direction to {@code FORWARD}, which is what {@code CRServoImpl} does here and what
     * {@link AbstractFakeDevice}'s no-op default would have missed. The commanded speed is deliberately
     * left alone, again matching the real device: this call reconfigures the handle, it does not stop the
     * servo.
     */
    @Override
    public void resetDeviceConfigurationForOpMode() {
        this.direction = Direction.FORWARD;
    }
}
