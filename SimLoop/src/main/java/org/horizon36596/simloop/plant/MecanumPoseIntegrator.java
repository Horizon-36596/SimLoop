package org.horizon36596.simloop.plant;

import org.horizon36596.simloop.config.DrivetrainSimConfig;

/**
 * Kinematic mecanum forward-kinematics pose integrator (architecture §3 core primitive,
 * fakehardware-plant §5). Given the four wheel linear speeds (in/s) it computes chassis velocity,
 * integrates the field-frame pose, and clamps to the field bounds (kinematic drivetrain bound, R6).
 *
 * <p>Kinematic only — no contact forces or rigid-body physics (R6). Geometry comes from the season
 * {@link DrivetrainSimConfig}; the core hard-codes no season numbers.
 *
 * <p>Wheel order is [frontLeft, frontRight, backLeft, backRight]. Field frame: +x forward at
 * heading 0, +y left, heading CCW-positive (radians).
 */
public final class MecanumPoseIntegrator {

    private final double halfSum;        // (wheelBase/2 + trackWidth/2), the mecanum lever arm
    private final double fieldHalfWidth;
    private final double fieldHalfHeight;

    /**
     * How much of the wheels' sideways contribution actually reaches the floor, as a fraction in
     * {@code (0, 1]} — {@link DrivetrainSimConfig#maxLateralVelocityTicksPerSecond()} divided by
     * {@link DrivetrainSimConfig#maxVelocityTicksPerSecond()}.
     *
     * <p>This is the mecanum's roller slip, and it is the one place this integrator stops being pure
     * geometry. The forward-kinematics sum below says what the wheels are <i>asking</i> the chassis to do
     * sideways; this factor says how much of that ask the rollers deliver. 1.0 means a robot that strafes
     * exactly as fast as it drives, which is what this class assumed before the factor existed.
     *
     * <p>It multiplies <b>only</b> the lateral velocity. Forward speed and rotation are unaffected, because
     * both are produced by the wheels rolling in their primary direction, where nothing slips.
     *
     * <p><b>This is not a friction model, and the drivetrain stays kinematic (domain R6).</b> Worth saying
     * plainly, because the words "slip" and "rollers" invite the opposite reading. Nothing here computes a
     * force, a normal load, or a contact patch: it is one measured constant, fixed for the life of the
     * object, multiplied once per tick — the same kind of thing as the top-speed ceiling the config already
     * supplies. If a future change wants slip that varies with load, speed or surface, that is rigid-body
     * physics and R6 says to stop and flag it rather than write it here.
     */
    private final double lateralEfficiency;

    private double x = 0.0;
    private double y = 0.0;
    private double heading = 0.0;

    /**
     * Builds an integrator sitting at the field origin, facing {@code +X}.
     *
     * @param config the drivetrain's geometry and speed ceilings; wheel base and track width set the
     *               rotation arm, and the two velocity ceilings set the roller-slip factor
     */
    public MecanumPoseIntegrator(DrivetrainSimConfig config) {
        this.halfSum = (config.wheelBase() / 2.0) + (config.trackWidth() / 2.0);
        this.fieldHalfWidth = config.fieldHalfWidth();
        this.fieldHalfHeight = config.fieldHalfHeight();

        double forward = config.maxVelocityTicksPerSecond();
        double lateral = config.maxLateralVelocityTicksPerSecond();
        if (forward <= 0) {
            throw new IllegalArgumentException("maxVelocityTicksPerSecond must be positive, got " + forward);
        }
        if (lateral <= 0 || lateral > forward) {
            throw new IllegalArgumentException(
                    "maxLateralVelocityTicksPerSecond must be positive and no greater than the forward"
                            + " figure (a mecanum is never faster sideways than forwards); forward was "
                            + forward + ", lateral was " + lateral);
        }
        this.lateralEfficiency = lateral / forward;
    }

    /**
     * Advance the pose by one tick from the four wheel linear speeds.
     *
     * @param frontLeft  front-left wheel's linear speed at the floor, in inches/second
     * @param frontRight front-right wheel's linear speed at the floor, in inches/second
     * @param backLeft   back-left wheel's linear speed at the floor, in inches/second
     * @param backRight  back-right wheel's linear speed at the floor, in inches/second
     * @param deltaTime  the tick length, in seconds
     */
    public void integrate(double frontLeft, double frontRight, double backLeft, double backRight, double deltaTime) {
        // Mecanum forward kinematics (robot frame): forward vx, left vy, CCW omega.
        double vx = (frontLeft + frontRight + backLeft + backRight) / 4.0;
        double vy = (-frontLeft + frontRight + backLeft - backRight) / 4.0;
        double omega = (-frontLeft + frontRight - backLeft + backRight) / (4.0 * halfSum);

        // Roller slip: the wheels ask for vy sideways, the rollers deliver rather less of it. Applied here
        // and only to vy, so forward motion and rotation stay exact geometry. See lateralEfficiency.
        vy *= lateralEfficiency;

        // Rotate robot-frame velocity into the field frame at the current heading, then integrate.
        double cos = Math.cos(heading);
        double sin = Math.sin(heading);
        x += (vx * cos - vy * sin) * deltaTime;
        y += (vx * sin + vy * cos) * deltaTime;
        heading += omega * deltaTime;

        // Kinematic field clamp (domain R6).
        x = clamp(x, -fieldHalfWidth, fieldHalfWidth);
        y = clamp(y, -fieldHalfHeight, fieldHalfHeight);
    }

    /** {@return the field-frame X position, in inches, clamped to the field} */
    public double getX() { return x; }

    /** {@return the field-frame Y position, in inches, clamped to the field} */
    public double getY() { return y; }

    /** {@return the heading in radians, CCW-positive, measured from field {@code +X}; not wrapped} */
    public double getHeading() { return heading; }

    /**
     * Teleports the pose, for a test that needs to start somewhere other than the origin.
     *
     * @param x       field-frame X position, in inches
     * @param y       field-frame Y position, in inches
     * @param heading heading in radians, CCW-positive from field {@code +X}
     */
    public void setPose(double x, double y, double heading) {
        this.x = x;
        this.y = y;
        this.heading = heading;
    }

    /** Returns the pose to the field origin, facing field {@code +X}. */
    public void reset() {
        setPose(0.0, 0.0, 0.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
