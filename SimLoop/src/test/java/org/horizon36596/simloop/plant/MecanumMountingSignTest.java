package org.horizon36596.simloop.plant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qualcomm.robotcore.hardware.DcMotorSimple;
import org.horizon36596.simloop.config.DrivetrainSimConfig;
import org.horizon36596.simloop.fakehardware.FakeMotor;

import org.junit.jupiter.api.Test;

/**
 * INVARIANT-tier (domain R9) — the Contract: <b>the sim models both signs that decide which way a wheel
 * turns the robot: how the motor is bolted in ({@link DrivetrainSimConfig#wheelMountingSigns()}) and how the
 * code reverses it ({@code DcMotorSimple.Direction}). A robot drives straight in the sim when, and only
 * when, those two agree — the same condition as on the field.</b>
 *
 * <p>Why this is worth pinning rather than trusting: before 2026-09-02 the core modelled only the software
 * direction, so the sim was implicitly a robot with all four motors mounted identically. The real robot has
 * its right-hand pair mirrored and reverses them in software, and the moment those real directions were
 * copied into the code the sim drove in circles — <b>a correct configuration failing in the sim, and, worse,
 * an incorrect one having passed</b>. The invariant survives any redesign of the plant because it is a
 * statement about modelling physical reality, not about this integrator.
 *
 * <p>Nothing here reads a clock: the plant is stepped with an explicit {@code deltaTime} (domain R5).
 */
class MecanumMountingSignTest {

    private static final double DT = 0.02;

    /**
     * Loop count, chosen to stay <b>off the plant's field clamp</b>. At this config's 93 in/s the robot
     * would travel about 136 inches in 100 loops and pin against {@code fieldHalfWidth} (72 in), where every
     * distance assertion below would be measuring the clamp instead of the wheels. Twenty loops is ~0.4 s,
     * about 11 inches through the first-order lag — clearly moving, nowhere near the wall.
     */
    private static final int LOOPS = 20;

    /** Long enough for the first-order motor model to be clearly moving, short enough to stay off the clamp. */
    private static final double DISTANCE_THAT_COUNTS_AS_MOVED_INCHES = 1.0;

    /** Right-hand motors mounted mirror-image: [FL, FR, BL, BR]. This is the real robot's build. */
    private static final double[] RIGHT_SIDE_MIRRORED = {1.0, -1.0, 1.0, -1.0};

    private static DrivetrainSimConfig config(double[] mountingSigns) {
        return new DrivetrainSimConfig() {
            @Override public double trackWidth() { return 11.27362; }
            @Override public double wheelBase() { return 11.50976; }
            @Override public double wheelRadius() { return 1.88976; }
            @Override public double ticksPerInch() { return 100.0; }
            @Override public double maxVelocityTicksPerSecond() { return 93.0 * 100.0; }
            @Override public double maxAccel() { return 1.8; }
            @Override public double fieldHalfWidth() { return 72.0; }
            @Override public double fieldHalfHeight() { return 72.0; }
            @Override public double[] wheelMountingSigns() { return mountingSigns; }
        };
    }

    /**
     * The real robot's configuration: right-hand motors mirrored in metal and {@code REVERSE} in code. Full
     * forward power on all four must drive the robot forward and hardly turn it at all.
     */
    @Test
    void mirroredMotorsReversedInSoftwareDriveStraightForward() {
        double[] pose = driveFullForward(RIGHT_SIDE_MIRRORED, DcMotorSimple.Direction.REVERSE);

        assertTrue(pose[0] > DISTANCE_THAT_COUNTS_AS_MOVED_INCHES,
                "a robot whose mirrored motors are reversed in software must drive FORWARD; x was " + pose[0]);
        assertEquals(0.0, pose[1], 1e-9,
                "driving straight must not drift sideways");
        assertEquals(0.0, pose[2], 1e-9,
                "driving straight must not rotate");
    }

    /**
     * The same robot with the software reversal forgotten — the mistake this test exists to make visible. It
     * must spin rather than drive, because the two sides now fight each other.
     */
    @Test
    void mirroredMotorsLeftForwardInSoftwareSpinInsteadOfDriving() {
        double[] pose = driveFullForward(RIGHT_SIDE_MIRRORED, DcMotorSimple.Direction.FORWARD);

        assertTrue(Math.abs(pose[2]) > 0.1,
                "with the software reversal missing, the two sides fight and the robot must TURN; heading was "
                        + pose[2] + " rad");
        assertTrue(Math.abs(pose[0]) < DISTANCE_THAT_COUNTS_AS_MOVED_INCHES,
                "and it must not travel forward while doing it; x was " + pose[0]);
    }

    /**
     * The interface default — every motor mounted the same way — must behave exactly as the core did before
     * mounting signs existed: no software reversal needed, and full forward power drives forward.
     *
     * <p>This one deliberately runs a config that does <b>not</b> override {@code wheelMountingSigns()}, so
     * the code under test is {@link DrivetrainSimConfig}'s own default body. Passing {@code {1,1,1,1}}
     * explicitly would assert the same numbers while never executing the default — and the default is the
     * whole promise that an existing season config keeps working untouched.
     */
    @Test
    void theAllForwardDefaultNeedsNoSoftwareReversal() {
        double[] pose = driveFullForward(null, DcMotorSimple.Direction.FORWARD);

        assertTrue(pose[0] > DISTANCE_THAT_COUNTS_AS_MOVED_INCHES,
                "with all motors mounted alike, plain forward power must drive forward; x was " + pose[0]);
        assertEquals(0.0, pose[2], 1e-9, "and must not rotate");
    }

    /** A config that returns the wrong number of signs is a programming error, and must say so at build time. */
    @Test
    void aMalformedSignArrayFailsLoudlyWhenThePlantIsBuilt() {
        FakeMotor[] motors = newMotors(DcMotorSimple.Direction.FORWARD);

        assertThrows(IllegalArgumentException.class,
                () -> new MecanumDrivePlant(motors, config(new double[] {1.0, -1.0})),
                "three-wheel-shaped sign arrays must not be silently accepted");
        assertThrows(IllegalArgumentException.class,
                () -> new MecanumDrivePlant(motors, config(null)),
                "a null sign array must not be silently accepted");
        assertThrows(IllegalArgumentException.class,
                () -> new MecanumDrivePlant(motors, config(new double[] {1.0, -0.9, 1.0, -1.0})),
                "a sign that is not exactly +1 or -1 is a per-wheel gain in disguise and must be rejected");
    }

    /**
     * Runs full forward power on all four motors and returns the resulting pose.
     *
     * @param mountingSigns how the motors are bolted in, or {@code null} to use the interface default
     * @param rightSide     the software direction applied to the two right-hand motors
     * @return {@code [x, y, heading]} — inches, inches, radians
     */
    private static double[] driveFullForward(double[] mountingSigns, DcMotorSimple.Direction rightSide) {
        FakeMotor[] motors = newMotors(rightSide);
        DrivetrainSimConfig config = mountingSigns == null
                ? configWithoutMountingSigns()
                : config(mountingSigns);
        MecanumDrivePlant plant = new MecanumDrivePlant(motors, config);

        for (FakeMotor motor : motors) {
            // What a driver pushing the stick fully forward asks of all four wheels. Any correct
            // combination of mounting and direction turns this into forward motion.
            motor.setPower(1.0);
        }
        for (int loop = 0; loop < LOOPS; loop++) {
            for (FakeMotor motor : motors) {
                motor.update(DT);
            }
            plant.update(DT);
        }
        return new double[] {plant.getX(), plant.getY(), plant.getHeading()};
    }

    /**
     * The same config with {@code wheelMountingSigns()} left alone, so the interface's default body runs.
     *
     * <p>Spelled out as a second anonymous class rather than folded into {@link #config(double[])} with a
     * null check, because a config that "sometimes overrides a method" is exactly the kind of cleverness
     * that makes a test look like it covers a default when it does not.
     */
    private static DrivetrainSimConfig configWithoutMountingSigns() {
        return new DrivetrainSimConfig() {
            @Override public double trackWidth() { return 11.27362; }
            @Override public double wheelBase() { return 11.50976; }
            @Override public double wheelRadius() { return 1.88976; }
            @Override public double ticksPerInch() { return 100.0; }
            @Override public double maxVelocityTicksPerSecond() { return 93.0 * 100.0; }
            @Override public double maxAccel() { return 1.8; }
            @Override public double fieldHalfWidth() { return 72.0; }
            @Override public double fieldHalfHeight() { return 72.0; }
            // wheelMountingSigns() deliberately NOT overridden — that is the point of this config.
        };
    }

    /** Four fake motors in [FL, FR, BL, BR] order, with the given direction on the right-hand pair. */
    private static FakeMotor[] newMotors(DcMotorSimple.Direction rightSide) {
        DrivetrainSimConfig config = config(new double[] {1.0, 1.0, 1.0, 1.0});
        FakeMotor[] motors = new FakeMotor[4];
        for (int i = 0; i < 4; i++) {
            motors[i] = new FakeMotor(config.maxVelocityTicksPerSecond(), config.maxAccel());
        }
        motors[1].setDirection(rightSide); // FR
        motors[3].setDirection(rightSide); // BR
        return motors;
    }
}
