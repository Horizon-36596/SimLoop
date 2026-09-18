package org.horizon36596.simloop.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Batch 1.2 trivial JVM test: proves the SimLoop module compiles and its test sourceset runs on a
 * desktop JVM with no Android, and that the season-glue interface surface is implementable.
 */
class SimRobotConfigContractTest {

    @Test
    void seasonGlueInterfacesAreImplementableOnTheJvm() {
        DrivetrainSimConfig drivetrain = new DrivetrainSimConfig() {
            @Override public double trackWidth() { return 11.27362; }
            @Override public double wheelBase() { return 11.50976; }
            @Override public double wheelRadius() { return 1.88976; }
            @Override public double ticksPerInch() { return 100.0; }
            @Override public double maxVelocityTicksPerSecond() { return 9300.0; }
            @Override public double maxAccel() { return 1.8; }
            @Override public double fieldHalfWidth() { return 72.0; }
            @Override public double fieldHalfHeight() { return 72.0; }
        };

        SimRobotConfig config = new SimRobotConfig() {
            @Override public String[] driveMotorNames() { return new String[] {"FL", "FR", "BL", "BR"}; }
            @Override public String odometryName() { return "octoquad"; }
            @Override public DrivetrainSimConfig drivetrain() { return drivetrain; }
        };

        assertArrayEquals(new String[] {"FL", "FR", "BL", "BR"}, config.driveMotorNames());
        assertEquals("octoquad", config.odometryName());
        assertSame(drivetrain, config.drivetrain());
        assertEquals(11.27362, config.drivetrain().trackWidth());
    }
}
