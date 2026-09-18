package org.horizon36596.simloop.plant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.horizon36596.simloop.config.DrivetrainSimConfig;
import org.horizon36596.simloop.fakehardware.FakeHardwareMap;
import org.horizon36596.simloop.fakehardware.FakeMotor;
import org.horizon36596.simloop.sim.FakeTimer;

import org.junit.jupiter.api.Test;

/**
 * Batch 1.4 determinism: the core plant (fake motors + mecanum integrator + FakeTimer) run twice
 * with an identical command schedule produces a byte-for-byte identical pose trajectory. This is the
 * deterministic substrate the Claude loop (Phase 3) depends on (sim-harness §5, domain R5).
 */
class PlantDeterminismTest {

    private static final double DT = 0.02;
    private static final int LOOPS = 250;

    private static final DrivetrainSimConfig CONFIG = new DrivetrainSimConfig() {
        @Override public double trackWidth() { return 11.27362; }
        @Override public double wheelBase() { return 11.50976; }
        @Override public double wheelRadius() { return 1.88976; }
        @Override public double ticksPerInch() { return 100.0; }
        @Override public double maxVelocityTicksPerSecond() { return 93.0 * 100.0; }
        @Override public double maxAccel() { return 1.8; }
        @Override public double fieldHalfWidth() { return 72.0; }
        @Override public double fieldHalfHeight() { return 72.0; }
    };

    @Test
    void identicalRunsProduceIdenticalTrajectory() {
        assertArrayEquals(runOnce(), runOnce(),
                "two identical plant runs must yield bit-identical pose trajectories");
    }

    /** One headless run; returns the flattened [x,y,heading] trajectory. */
    private static double[] runOnce() {
        FakeTimer timer = new FakeTimer();
        FakeHardwareMap map = new FakeHardwareMap();
        String[] names = {"FL", "FR", "BL", "BR"};
        FakeMotor[] motors = new FakeMotor[4];
        for (int i = 0; i < 4; i++) {
            motors[i] = new FakeMotor(CONFIG.maxVelocityTicksPerSecond(), CONFIG.maxAccel());
            map.register(names[i], motors[i]);
        }
        MecanumPoseIntegrator integrator = new MecanumPoseIntegrator(CONFIG);

        double[] trajectory = new double[LOOPS * 3];
        for (int loop = 0; loop < LOOPS; loop++) {
            // Scripted routine: forward, then arc, then strafe — deterministic, no wall-clock.
            double power = loop < 100 ? 1.0 : 0.5;
            double turn = loop >= 100 && loop < 175 ? 0.3 : 0.0;
            motors[0].setPower(power - turn); // FL
            motors[1].setPower(power + turn); // FR
            motors[2].setPower(power - turn); // BL
            motors[3].setPower(power + turn); // BR

            map.updateAll(DT);
            timer.advance(DT);

            double scale = CONFIG.maxVelocityTicksPerSecond() / CONFIG.ticksPerInch(); // ticks/s -> in/s
            integrator.integrate(
                    motors[0].getSpeed() * scale,
                    motors[1].getSpeed() * scale,
                    motors[2].getSpeed() * scale,
                    motors[3].getSpeed() * scale,
                    DT);

            trajectory[loop * 3] = integrator.getX();
            trajectory[loop * 3 + 1] = integrator.getY();
            trajectory[loop * 3 + 2] = integrator.getHeading();
        }
        return trajectory;
    }
}
