package org.horizon36596.simloop.config;

/**
 * Season-glue interface: the device registry the core needs to build a {@code FakeHardwareMap}
 * that mirrors the real robot configuration (architecture §3, domain R7). Implemented by the
 * season layer in {@code TeamCode}; the core never reaches out for these season facts.
 *
 * <p>Device names MUST match the names the real subsystems pass to {@code hardwareMap.get(...)}
 * so the same subsystem code resolves devices in sim and on the robot (domain R1).
 *
 * <p>Scope (Batch 1.2): interface surface only. {@code FakeHardwareMap} population from these
 * names is built in Batch 1.4.
 */
public interface SimRobotConfig {

    /** {@return the drive motor config names, in [frontLeft, frontRight, backLeft, backRight] order} */
    String[] driveMotorNames();

    /** {@return the config name of the odometry/localizer device the drivetrain reads pose from} */
    String odometryName();

    /** {@return the drivetrain geometry and motor-model constants for this season} */
    DrivetrainSimConfig drivetrain();
}
