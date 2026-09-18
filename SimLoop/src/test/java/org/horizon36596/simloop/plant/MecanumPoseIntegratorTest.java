package org.horizon36596.simloop.plant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.horizon36596.simloop.config.DrivetrainSimConfig;

import org.junit.jupiter.api.Test;

/** Batch 1.4: mecanum-FK pose integrator + field clamp (fakehardware-plant §5, domain R6). */
class MecanumPoseIntegratorTest {

    private static final double DT = 0.02;

    private static DrivetrainSimConfig config(final double fieldHalf) {
        return new DrivetrainSimConfig() {
            @Override public double trackWidth() { return 12.0; }
            @Override public double wheelBase() { return 12.0; }
            @Override public double wheelRadius() { return 2.0; }
            @Override public double ticksPerInch() { return 100.0; }
            @Override public double maxVelocityTicksPerSecond() { return 9300.0; }
            @Override public double maxAccel() { return 1.8; }
            @Override public double fieldHalfWidth() { return fieldHalf; }
            @Override public double fieldHalfHeight() { return fieldHalf; }
        };
    }

    @Test
    void equalForwardWheelSpeedsAdvancePlusXAtHeadingZero() {
        MecanumPoseIntegrator integrator = new MecanumPoseIntegrator(config(72.0));
        for (int i = 0; i < 50; i++) {
            integrator.integrate(20.0, 20.0, 20.0, 20.0, DT); // 20 in/s forward
        }
        assertTrue(integrator.getX() > 0.0, "forward command should advance +x");
        assertEquals(0.0, integrator.getY(), 1e-9, "no strafe -> y stays 0");
        assertEquals(0.0, integrator.getHeading(), 1e-9, "no rotation -> heading stays 0");
    }

    @Test
    void poseIsClampedToFieldBounds() {
        MecanumPoseIntegrator integrator = new MecanumPoseIntegrator(config(10.0));
        for (int i = 0; i < 1000; i++) {
            integrator.integrate(100.0, 100.0, 100.0, 100.0, DT); // drive far past the bound
        }
        assertEquals(10.0, integrator.getX(), 1e-9, "x clamped to field half-width");
    }

    @Test
    void oppositeSideSpeedsProduceRotation() {
        MecanumPoseIntegrator integrator = new MecanumPoseIntegrator(config(72.0));
        for (int i = 0; i < 10; i++) {
            integrator.integrate(-10.0, 10.0, -10.0, 10.0, DT); // right side forward -> CCW turn
        }
        assertTrue(integrator.getHeading() > 0.0, "should rotate CCW (+heading)");
    }
}
