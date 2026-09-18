package org.horizon36596.simloop.plant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.horizon36596.simloop.config.DrivetrainSimConfig;
import org.horizon36596.simloop.fakehardware.FakeMotor;

import org.junit.jupiter.api.Test;

/**
 * INVARIANT-tier (domain R9) — the sim odometry loop: {@link MecanumDrivePlant} integrates a sensible
 * field pose from the drive motors' modelled wheel speeds, and does so deterministically (R5). These
 * hold under any redesign of the plant internals.
 */
class MecanumDrivePlantTest {

    private static DrivetrainSimConfig config() {
        return config(26.2);
    }

    private static DrivetrainSimConfig config(double ticksPerInch) {
        return new DrivetrainSimConfig() {
            @Override public double trackWidth() { return 11.27362; }
            @Override public double wheelBase() { return 11.50976; }
            @Override public double wheelRadius() { return 1.88976; }
            @Override public double ticksPerInch() { return ticksPerInch; }
            @Override public double maxVelocityTicksPerSecond() { return 2435.0; }
            @Override public double maxAccel() { return 2.0; }
            @Override public double fieldHalfWidth() { return 72.0; }
            @Override public double fieldHalfHeight() { return 72.0; }
        };
    }

    private static FakeMotor[] fourMotors() {
        return new FakeMotor[] {
                new FakeMotor(2435.0, 2.0), new FakeMotor(2435.0, 2.0),
                new FakeMotor(2435.0, 2.0), new FakeMotor(2435.0, 2.0),
        };
    }

    @Test
    void forwardWheelCommandAdvancesPoseForward() {
        FakeMotor[] motors = fourMotors();
        MecanumDrivePlant plant = new MecanumDrivePlant(motors, config());

        for (FakeMotor m : motors) m.setPower(1.0); // all wheels forward
        for (int i = 0; i < 100; i++) {
            for (FakeMotor m : motors) m.update(0.02);
            plant.update(0.02);
        }

        assertTrue(plant.getX() > 0.0, "all-wheels-forward must move +x (forward)");
        assertEquals(0.0, plant.getY(), 1e-6, "symmetric forward drive must not strafe");
        assertEquals(0.0, plant.getHeading(), 1e-6, "symmetric forward drive must not rotate");
    }

    @Test
    void differentialWheelsRotateInPlace() {
        FakeMotor[] motors = fourMotors();
        MecanumDrivePlant plant = new MecanumDrivePlant(motors, config());

        // Right side forward, left side reverse -> CCW rotation (omega > 0), no net translation.
        motors[0].setPower(-1.0); // FL
        motors[1].setPower(1.0);  // FR
        motors[2].setPower(-1.0); // BL
        motors[3].setPower(1.0);  // BR
        for (int i = 0; i < 100; i++) {
            for (FakeMotor m : motors) m.update(0.02);
            plant.update(0.02);
        }

        assertTrue(plant.getHeading() > 0.0, "right-forward/left-reverse must rotate CCW (+heading)");
        assertEquals(0.0, plant.getX(), 1e-6, "pure rotation must not translate x");
        assertEquals(0.0, plant.getY(), 1e-6, "pure rotation must not translate y");
    }

    @Test
    void ticksPerInchScalesTheIntegratedDisplacement() {
        // The wrapper's own contribution over the integrator is ticks/s -> in/s via ticksPerInch. Pin it:
        // doubling ticksPerInch halves the wheel in/s, so identical motors integrate exactly half the distance.
        FakeMotor[] slow = fourMotors();  // ticksPerInch = 2x -> slower in inches
        FakeMotor[] fast = fourMotors();  // ticksPerInch = 1x
        MecanumDrivePlant plantHalf = new MecanumDrivePlant(slow, config(52.4));
        MecanumDrivePlant plantFull = new MecanumDrivePlant(fast, config(26.2));

        for (FakeMotor m : slow) m.setPower(1.0);
        for (FakeMotor m : fast) m.setPower(1.0);
        // 20 ticks keeps the faster plant well under the 72 in field clamp, so the 2x ratio stays exact.
        for (int i = 0; i < 20; i++) {
            for (int m = 0; m < 4; m++) { slow[m].update(0.02); fast[m].update(0.02); }
            plantHalf.update(0.02);
            plantFull.update(0.02);
        }

        // Same motor velocities, ticksPerInch 2x -> exactly half the forward displacement (linear in 1/tpi).
        assertEquals(plantFull.getX(), 2.0 * plantHalf.getX(), 1e-9);
        assertTrue(plantFull.getX() > 0, "precondition: moved");
    }

    @Test
    void eachMotorMapsToItsWheelSlot() {
        // Drive ONLY the front-right wheel; the FK signature for FR-only is vx>0, vy>0, omega>0 — which
        // distinguishes the [FL, FR, BL, BR] plumbing from the integrator math (a mis-ordered slot would
        // flip vy/omega signs). Few ticks so heading stays small and x/y read the robot-frame velocity.
        FakeMotor[] motors = fourMotors();
        MecanumDrivePlant plant = new MecanumDrivePlant(motors, config());

        motors[1].setPower(1.0); // FR only
        for (int i = 0; i < 15; i++) {
            for (FakeMotor m : motors) m.update(0.02);
            plant.update(0.02);
        }

        assertTrue(plant.getX() > 0, "FR-only must give +x");
        assertTrue(plant.getY() > 0, "FR-only must give +y (left) — pins FR is in the right slot");
        assertTrue(plant.getHeading() > 0, "FR-only must give +heading (CCW) — pins FR is in the right slot");
    }

    @Test
    void identicalCommandSequenceProducesIdenticalPose() {
        FakeMotor[] a = fourMotors();
        FakeMotor[] b = fourMotors();
        MecanumDrivePlant pa = new MecanumDrivePlant(a, config());
        MecanumDrivePlant pb = new MecanumDrivePlant(b, config());

        double[] powers = {1.0, 0.8, -0.5, 0.3}; // an asymmetric command so x, y, heading all move
        for (int i = 0; i < 4; i++) { a[i].setPower(powers[i]); b[i].setPower(powers[i]); }
        for (int i = 0; i < 200; i++) {
            for (int m = 0; m < 4; m++) { a[m].update(0.02); b[m].update(0.02); }
            pa.update(0.02);
            pb.update(0.02);
        }

        // Zero tolerance: same inputs + same dt must yield bit-identical integrated pose (R5).
        assertEquals(pa.getX(), pb.getX(), 0.0);
        assertEquals(pa.getY(), pb.getY(), 0.0);
        assertEquals(pa.getHeading(), pb.getHeading(), 0.0);
    }
}
