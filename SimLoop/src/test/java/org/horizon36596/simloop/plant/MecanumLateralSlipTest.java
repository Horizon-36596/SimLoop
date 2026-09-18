package org.horizon36596.simloop.plant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.horizon36596.simloop.config.DrivetrainSimConfig;

import org.junit.jupiter.api.Test;

/**
 * INVARIANT-tier (domain R9) — the Contract: <b>a simulated mecanum travels sideways more slowly than it
 * travels forwards, by exactly the ratio the season config measured, and its forward speed and rotation are
 * untouched by that penalty.</b>
 *
 * <p>Why this is worth pinning rather than trusting. Strafing spins the wheels just as fast as driving
 * forward, but the chassis does not travel as far, because sideways motion comes from the rollers and
 * rollers slip. On the V0 robot the two were measured on 2026-08-30 as 96.7 in/s forward and 84.6 in/s
 * sideways. Until 2026-09-02 the core modelled one speed for every direction, so the simulated robot
 * strafed about 14% faster than <i>its own path follower</i> believed it could — and a follower that
 * under-brakes every lateral move shows overshoot the real robot does not have.
 *
 * <p>The invariant is a statement about mecanum wheels, not about this integrator, so it survives any
 * redesign of the pose maths. Nothing here reads a clock: the integrator is stepped with an explicit
 * {@code deltaTime} (domain R5).
 */
class MecanumLateralSlipTest {

    private static final double DT = 0.02;

    /** One second of simulated driving — long enough to compare distances, short of any field bound. */
    private static final int LOOPS = 50;

    /** Wheel speed used for every run below, in/s. Arbitrary but well inside the config's ceiling. */
    private static final double WHEEL_SPEED_IN_PER_SEC = 20.0;

    /** The V0 robot's measured pair, in/s (2026-08-30): it loses about 13% going sideways. */
    private static final double MEASURED_FORWARD_IN_PER_SEC = 96.7176;
    private static final double MEASURED_STRAFE_IN_PER_SEC = 84.5661;

    /**
     * A strafe command must move the robot sideways by the forward distance scaled by the measured ratio —
     * no more (which would out-run the follower) and no less (which would invent drag nobody measured).
     */
    @Test
    void strafingCoversLessGroundThanDrivingByTheMeasuredRatio() {
        double forwardDistance = driveOneSecond(Direction.FORWARD, measuredConfig());
        double strafeDistance = driveOneSecond(Direction.LEFT, measuredConfig());

        double expectedRatio = MEASURED_STRAFE_IN_PER_SEC / MEASURED_FORWARD_IN_PER_SEC;
        assertTrue(strafeDistance < forwardDistance,
                "a mecanum must cover less ground sideways than forwards; forward was " + forwardDistance
                        + " in and strafe was " + strafeDistance + " in");
        assertEquals(expectedRatio, strafeDistance / forwardDistance, 1e-9,
                "the strafe penalty must be exactly the measured ratio, not an approximation of it");
    }

    /**
     * The penalty applies to sideways motion only. A robot driving forward is rolling its wheels in their
     * primary direction, where nothing slips, so the roller factor must not touch that distance.
     */
    @Test
    void theSlipPenaltyDoesNotSlowForwardDrivingDown() {
        double withPenalty = driveOneSecond(Direction.FORWARD, measuredConfig());
        double withoutPenalty = driveOneSecond(Direction.FORWARD, configWithoutLateralOverride());

        assertEquals(withoutPenalty, withPenalty, 1e-9,
                "declaring a strafe speed must not change how far the robot drives forward");
    }

    /** Rotation is likewise produced by the wheels rolling, so it must be unaffected too. */
    @Test
    void theSlipPenaltyDoesNotSlowRotationDown() {
        double withPenalty = rotateOneSecond(measuredConfig());
        double withoutPenalty = rotateOneSecond(configWithoutLateralOverride());

        assertTrue(Math.abs(withPenalty) > 1e-6, "the robot must actually be turning for this to mean anything");
        assertEquals(withoutPenalty, withPenalty, 1e-9,
                "declaring a strafe speed must not change how fast the robot rotates");
    }

    /**
     * A season that has not measured its strafe speed must behave exactly as this core did before the number
     * existed — no penalty at all. This runs a config that does <b>not</b> override the lateral accessor, so
     * the code under test is {@link DrivetrainSimConfig}'s own default body rather than a value that happens
     * to equal it.
     */
    @Test
    void theDefaultAppliesNoPenaltyAtAll() {
        double forwardDistance = driveOneSecond(Direction.FORWARD, configWithoutLateralOverride());
        double strafeDistance = driveOneSecond(Direction.LEFT, configWithoutLateralOverride());

        assertEquals(forwardDistance, strafeDistance, 1e-9,
                "with no strafe speed declared, the sim must strafe exactly as fast as it drives");
    }

    /**
     * A mecanum is never faster sideways than forwards, so a lateral figure above the forward one is a paste
     * error rather than an unusual robot, and must fail at build time instead of quietly modelling a chassis
     * that cannot exist. Zero and negative are rejected for the same reason.
     */
    @Test
    void anImpossibleLateralSpeedFailsLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> new MecanumPoseIntegrator(configWithLateral(MEASURED_FORWARD_IN_PER_SEC + 1.0)),
                "a robot faster sideways than forwards must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new MecanumPoseIntegrator(configWithLateral(0.0)),
                "a zero strafe speed must be rejected rather than freezing lateral motion silently");
        assertThrows(IllegalArgumentException.class,
                () -> new MecanumPoseIntegrator(configWithLateral(-MEASURED_STRAFE_IN_PER_SEC)),
                "a negative strafe speed must be rejected");
    }

    /**
     * The other end of the same contract: {@code lateral == forward} is the documented <b>valid</b> upper
     * bound — a robot exactly as fast sideways as forwards — and must be accepted.
     *
     * <p>Pinned separately because the rejection test above cannot see it. The guard is
     * {@code lateral <= 0 || lateral > forward}, and tightening that {@code >} to {@code >=} would still
     * pass every rejection case while breaking the interface's own default, which returns exactly the
     * forward figure. That default is what every season config that has not measured its strafe speed
     * relies on, so the boundary is load-bearing rather than academic.
     */
    @Test
    void aRobotExactlyAsFastSidewaysAsForwardsIsAccepted() {
        MecanumPoseIntegrator integrator =
                new MecanumPoseIntegrator(configWithLateral(MEASURED_FORWARD_IN_PER_SEC));

        // Constructing it is the assertion; drive it one tick so the test also fails if the boundary case
        // is accepted but then produces a broken (zero, NaN) lateral scale.
        integrator.integrate(-WHEEL_SPEED_IN_PER_SEC, WHEEL_SPEED_IN_PER_SEC,
                WHEEL_SPEED_IN_PER_SEC, -WHEEL_SPEED_IN_PER_SEC, DT);
        assertEquals(WHEEL_SPEED_IN_PER_SEC * DT, integrator.getY(), 1e-9,
                "at the upper bound there is no penalty, so a strafe command must arrive in full");
    }

    /** Which way the wheels are being asked to drive the chassis. */
    private enum Direction { FORWARD, LEFT }

    /**
     * Runs one second of driving at {@link #WHEEL_SPEED_IN_PER_SEC} and returns the distance travelled along
     * the commanded axis, in inches.
     *
     * <p>The wheel-speed patterns are mecanum inverse kinematics written out longhand: all four equal drives
     * forward, and the {@code [-, +, +, -]} pattern drives left. Spelling them out rather than calling an IK
     * helper keeps this test independent of the drivetrain code it is meant to check.
     */
    private static double driveOneSecond(Direction direction, DrivetrainSimConfig config) {
        MecanumPoseIntegrator integrator = new MecanumPoseIntegrator(config);
        double v = WHEEL_SPEED_IN_PER_SEC;
        for (int loop = 0; loop < LOOPS; loop++) {
            if (direction == Direction.FORWARD) {
                integrator.integrate(v, v, v, v, DT);
            } else {
                integrator.integrate(-v, v, v, -v, DT);
            }
        }
        // Heading stays 0 throughout, so the robot frame and the field frame coincide: x is forward
        // travel and y is leftward travel.
        return direction == Direction.FORWARD ? integrator.getX() : integrator.getY();
    }

    /** Runs one second of rotation in place and returns the heading reached, radians. */
    private static double rotateOneSecond(DrivetrainSimConfig config) {
        MecanumPoseIntegrator integrator = new MecanumPoseIntegrator(config);
        double v = WHEEL_SPEED_IN_PER_SEC;
        for (int loop = 0; loop < LOOPS; loop++) {
            // [-, +, -, +] — the rotation pattern: both left wheels back, both right wheels forward.
            integrator.integrate(-v, v, -v, v, DT);
        }
        return integrator.getHeading();
    }

    /** The V0 robot's measured pair. */
    private static DrivetrainSimConfig measuredConfig() {
        return configWithLateral(MEASURED_STRAFE_IN_PER_SEC);
    }

    /** The same geometry with an explicit lateral speed, so bad values can be handed to the integrator. */
    private static DrivetrainSimConfig configWithLateral(double lateralInPerSec) {
        return new DrivetrainSimConfig() {
            @Override public double trackWidth() { return 13.5748; }
            @Override public double wheelBase() { return 10.6555; }
            @Override public double wheelRadius() { return 2.04724; }
            @Override public double ticksPerInch() { return 26.2; }
            @Override public double maxVelocityTicksPerSecond() { return MEASURED_FORWARD_IN_PER_SEC * 26.2; }
            @Override public double maxLateralVelocityTicksPerSecond() { return lateralInPerSec * 26.2; }
            @Override public double maxAccel() { return 2.0; }
            @Override public double fieldHalfWidth() { return 72.0; }
            @Override public double fieldHalfHeight() { return 72.0; }
        };
    }

    /**
     * The same config with {@code maxLateralVelocityTicksPerSecond()} left alone, so the interface's default
     * body runs.
     *
     * <p>Spelled out as a second anonymous class rather than folded into {@link #configWithLateral(double)}
     * with a sentinel, because a config that "sometimes overrides a method" is the kind of cleverness that
     * makes a test look like it covers a default when it does not.
     */
    private static DrivetrainSimConfig configWithoutLateralOverride() {
        return new DrivetrainSimConfig() {
            @Override public double trackWidth() { return 13.5748; }
            @Override public double wheelBase() { return 10.6555; }
            @Override public double wheelRadius() { return 2.04724; }
            @Override public double ticksPerInch() { return 26.2; }
            @Override public double maxVelocityTicksPerSecond() { return MEASURED_FORWARD_IN_PER_SEC * 26.2; }
            @Override public double maxAccel() { return 2.0; }
            @Override public double fieldHalfWidth() { return 72.0; }
            @Override public double fieldHalfHeight() { return 72.0; }
            // maxLateralVelocityTicksPerSecond() deliberately NOT overridden — that is the point.
        };
    }
}
