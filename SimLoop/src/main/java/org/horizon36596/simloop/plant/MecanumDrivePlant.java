package org.horizon36596.simloop.plant;

import org.horizon36596.simloop.config.DrivetrainSimConfig;
import org.horizon36596.simloop.fakehardware.FakeMotor;

/**
 * Closes the sim odometry loop for a mecanum drivetrain (fakehardware-plant §5): each tick it reads the
 * four drive {@link FakeMotor}s' modelled wheel velocities and integrates the field-frame pose through
 * {@link MecanumPoseIntegrator}. Because the season drivetrain's inverse kinematics (which commanded the
 * motor powers) and this forward-kinematics integrator share the {@code [frontLeft, frontRight, backLeft,
 * backRight]} wheel order and convention (B1-M1 — IK is the exact inverse of this FK), a forward chassis
 * command drives the pose forward: the sim odometry is self-consistent with what was commanded.
 *
 * <p>Season-agnostic core: it depends only on {@link FakeMotor} + {@link DrivetrainSimConfig}. The glue
 * that exposes this pose through the season's odometry device (e.g. an OctoQuad fake) lives in the season
 * layer (domain R7). Deterministic: state changes only in {@link #update(double)}; getters are pure reads.
 */
public final class MecanumDrivePlant {

    private final FakeMotor frontLeft;
    private final FakeMotor frontRight;
    private final FakeMotor backLeft;
    private final FakeMotor backRight;
    private final double ticksPerInch;
    private final MecanumPoseIntegrator integrator;

    /**
     * Per-wheel physical mounting signs, {@code [FL, FR, BL, BR]} — see
     * {@link DrivetrainSimConfig#wheelMountingSigns()}. Applied to each motor's modelled shaft velocity on
     * the way into the forward kinematics, because the integrator's convention is "positive means this
     * corner is driving the chassis forward" and a mirrored motor's shaft turns the other way to do that.
     */
    private final double[] mountingSigns;

    /**
     * Wires the plant to the four fake motors the robot code already drives.
     *
     * @param motors drive motors in {@code [frontLeft, frontRight, backLeft, backRight]} order — the same
     *               order the season drivetrain's IK writes and {@link MecanumPoseIntegrator} reads.
     * @param config the drivetrain's geometry, ticks-per-inch and per-wheel mounting signs
     */
    public MecanumDrivePlant(FakeMotor[] motors, DrivetrainSimConfig config) {
        if (motors == null || motors.length != 4) {
            throw new IllegalArgumentException("mecanum drive needs exactly 4 motors [FL, FR, BL, BR]");
        }
        double tpi = config.ticksPerInch();
        if (tpi <= 0) {
            throw new IllegalArgumentException("ticksPerInch must be positive, got " + tpi);
        }
        this.frontLeft = motors[0];
        this.frontRight = motors[1];
        this.backLeft = motors[2];
        this.backRight = motors[3];
        this.ticksPerInch = tpi;
        double[] signs = config.wheelMountingSigns();
        if (signs == null || signs.length != 4) {
            throw new IllegalArgumentException(
                    "wheelMountingSigns must return exactly 4 values [FL, FR, BL, BR], got "
                            + (signs == null ? "null" : String.valueOf(signs.length)));
        }
        for (int wheel = 0; wheel < 4; wheel++) {
            // Only +1 and -1 mean anything: a motor is either mounted this way round or the other way.
            // A 0.9 here would silently become a per-wheel gain — a scaling fudge factor hiding in a field
            // whose javadoc says "a fact about the metal" — so it is rejected rather than interpreted.
            if (signs[wheel] != 1.0 && signs[wheel] != -1.0) {
                throw new IllegalArgumentException(
                        "wheelMountingSigns must be exactly +1 or -1 (which way the motor is bolted in);"
                                + " wheel " + wheel + " was " + signs[wheel]);
            }
        }
        this.mountingSigns = signs.clone();
        this.integrator = new MecanumPoseIntegrator(config);
    }

    /**
     * Integrate the pose one tick from the motors' current modelled PHYSICAL wheel velocities (ticks/s -&gt;
     * in/s), each corrected for how that motor is physically mounted. Reads {@code getPhysicalVelocityTicksPerSecond()}
     * rather than the SDK-reported {@code getVelocityTicksPerSecond()} (BACKLOG B18): this plant needs the
     * shaft's true physical rotation to combine with {@link #mountingSigns}, not the software-direction
     * abstraction a real robot's own code would read.
     *
     * @param deltaTime the tick length, in seconds
     */
    public void update(double deltaTime) {
        integrator.integrate(
                mountingSigns[0] * frontLeft.getPhysicalVelocityTicksPerSecond() / ticksPerInch,
                mountingSigns[1] * frontRight.getPhysicalVelocityTicksPerSecond() / ticksPerInch,
                mountingSigns[2] * backLeft.getPhysicalVelocityTicksPerSecond() / ticksPerInch,
                mountingSigns[3] * backRight.getPhysicalVelocityTicksPerSecond() / ticksPerInch,
                deltaTime);
    }

    /** {@return the field-frame X position, in inches — {@code +x} is forward at heading 0} */
    public double getX() { return integrator.getX(); }

    /** {@return the field-frame Y position, in inches — {@code +y} is left at heading 0} */
    public double getY() { return integrator.getY(); }

    /** {@return the heading in radians, CCW-positive, measured from field {@code +x}} */
    public double getHeading() { return integrator.getHeading(); }

    /**
     * Teleport the integrated pose (e.g. to seed a routine's start pose).
     *
     * @param x       field-frame X position, in inches
     * @param y       field-frame Y position, in inches
     * @param heading heading in radians, CCW-positive from field {@code +x}
     */
    public void setPose(double x, double y, double heading) {
        integrator.setPose(x, y, heading);
    }
}
