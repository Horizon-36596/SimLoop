package org.horizon36596.simloop.fakehardware;

import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.ServoController;

/**
 * Kinematic fake {@link Servo}: a commanded position is held instantly (a bare servo has no dynamics
 * worth modelling for the drivetrain sim — mechanism dynamics live in {@code Mechanism1DofPlant}, R6).
 * Stores the commanded position in [0, 1] and the direction; reads are pure. Lets the unchanged
 * SolversLib {@code ServoEx} wrapper construct and drive it headless.
 */
public class FakeServo extends AbstractFakeDevice implements Servo {

    /** Creates a servo commanded to position 0.0, FORWARD, with the full {@code [0, 1]} scale range. */
    public FakeServo() {
    }

    private double position = 0.0;          // scaled (physical) position last commanded, in [limitMin, limitMax]
    private Direction direction = Direction.FORWARD;
    private double limitMin = MIN_POSITION; // Servo.MIN_POSITION (0.0)
    private double limitMax = MAX_POSITION; // Servo.MAX_POSITION (1.0)

    @Override
    public void update(double deltaTime) {
        // Instantaneous: commanded position is the position. No dynamics for a bare servo.
    }

    @Override
    public void setPosition(double position) {
        // Exactly ServoImpl.setPosition's three steps, in its order: clip the [0,1] request, apply the
        // direction, then map into the scaled [limitMin, limitMax] window.
        //
        // The ORDER is the part that matters and the part that was wrong here until 2026-09-17. Reversing
        // before scaling means REVERSE + scaleRange(0.2, 0.6) + setPosition(0.2) puts the horn at 0.52;
        // reversing after scaling - or, as this class used to do, not at all - puts it at 0.28. A subsystem
        // that reverses a servo in hardware and tunes its endpoints against the sim would have been tuned
        // against a mirror image of the real robot.
        double clipped = Math.max(MIN_POSITION, Math.min(MAX_POSITION, position));
        double directed = direction == Direction.REVERSE ? reverse(clipped) : clipped;
        this.position = limitMin + directed * (limitMax - limitMin);
    }

    /** {@return the last commanded position on the user-facing {@code [0, 1]} scale, unitless} */
    @Override
    public double getPosition() {
        // Undo setPosition, in the mirrored order, exactly as ServoImpl.getPosition does: un-scale, un-apply
        // the direction, clip. Reversal applied twice cancels, so this still round-trips what was commanded -
        // which is why the direction bug above was invisible from getPosition() and only ever showed up in
        // getScaledPosition(), the physical value a sim mechanism actually reads.
        if (limitMax == limitMin) return limitMin;
        double unscaled = (position - limitMin) / (limitMax - limitMin);
        double undirected = direction == Direction.REVERSE ? reverse(unscaled) : unscaled;
        return Math.max(MIN_POSITION, Math.min(MAX_POSITION, undirected));
    }

    /** {@code ServoImpl.reverse}: mirrors a [0, 1] position about the middle of the range. */
    private static double reverse(double position) {
        return MAX_POSITION - position + MIN_POSITION;
    }

    /**
     * The scaled (physical) position actually applied, in {@code [limitMin, limitMax]} — the value a
     * downstream sim mechanism would see (analogous to {@code FakeMotor.getCurrentPosition()}). This is
     * where {@link #scaleRange(double, double)} is observable; {@link #getPosition()} reverses it.
     *
     * @return the physical position in {@code [limitMin, limitMax]}, unitless servo travel
     */
    public double getScaledPosition() { return position; }

    /**
     * Sets the direction. Deliberately does <b>not</b> invalidate the stored position.
     *
     * <p>{@code ServoImpl.setDirection} and {@code ServoImpl.scaleRange} both call
     * {@code controller.forgetLastKnownPosition(port)} when the mapping changes, and {@code getPosition()}'s
     * javadoc says it returns {@code Double.NaN} "if that is unavailable" — which reads like a contract that
     * this fake is breaking. On the Control Hub it is not. {@code LynxServoController.getServoPosition}
     * treats the forgotten value as a cache miss, not as an answer: it reads the channel's actual PWM pulse
     * width back and reconstructs the position from it, and only ever returns something other than the real
     * position when the pulse width is zero, i.e. the servo was never commanded. So on the hardware this
     * library simulates, a {@code scaleRange} or {@code setDirection} call does not make {@code getPosition()}
     * unreadable — it makes it re-derive the same physical position through the new mapping, which is
     * exactly what this class does by keeping {@link #position} and un-mapping it in {@link #getPosition()}.
     * The NaN wording covers other {@code ServoController} implementations that have no pulse-width readback.
     * (Raised and dismissed by the 2026-09-17 charter review's SDK-fidelity angle; recorded here because the
     * interface javadoc genuinely does read the other way.)
     */
    @Override public void setDirection(Direction direction) { this.direction = direction; }
    @Override public Direction getDirection() { return direction; }

    @Override
    public void scaleRange(double min, double max) {
        // SDK contract, read off ServoImpl.scaleRange rather than assumed: each endpoint is CLIPPED into
        // [0, 1] first, and the only thing that throws is an inverted range after clipping. So
        // scaleRange(-0.1, 0.5) is a legal request for [0.0, 0.5] on a real servo, not an error. This class
        // used to reject it, which made the fake stricter than the hardware - a test could go red against
        // the sim for a call that works on the robot, which is the fidelity rule failing in the direction
        // that wastes the most time.
        double clippedMin = Math.max(MIN_POSITION, Math.min(MAX_POSITION, min));
        double clippedMax = Math.max(MIN_POSITION, Math.min(MAX_POSITION, max));
        if (clippedMin >= clippedMax) {
            throw new IllegalArgumentException("min must be less than max");
        }
        this.limitMin = clippedMin;
        this.limitMax = clippedMax;
    }

    @Override public int getPortNumber() { return 0; }

    @Override
    public ServoController getController() {
        throw new UnsupportedOperationException(
                "FakeServo has no ServoController in sim — read state via getPosition()");
    }
}
