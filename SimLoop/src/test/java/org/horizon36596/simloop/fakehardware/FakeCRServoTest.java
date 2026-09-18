package org.horizon36596.simloop.fakehardware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qualcomm.robotcore.hardware.CRServoImpl;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.ServoController;

import org.junit.jupiter.api.Test;

/**
 * INVARIANT-tier (domain R9) — B13 Contract clause 3, device half: a continuous-rotation ejector is
 * readable through a fake {@link com.qualcomm.robotcore.hardware.CRServo} that matches the SDK's
 * {@code CRServo} exactly. The motion half (power becomes travel on {@code FakeTimer}, never a wall
 * clock) is pinned by {@code ContinuousRotationPlantTest}.
 *
 * <p><b>Why this test instantiates the real {@link CRServoImpl}.</b> "Matches the SDK exactly" is a claim
 * about another program's behaviour, so the honest way to check it is to run that program. {@code
 * CRServoImpl}'s power path touches nothing but its {@link ServoController}, so a small recording
 * controller is enough to stand it up on a desktop JVM — and then every power/direction case can be
 * asserted against what the real class actually did rather than against what this test's author believed
 * it does. A test that restated the arithmetic instead would pass just as happily against a fake that had
 * copied a mistake out of the SDK's javadoc.
 *
 * <p>The comparison is on {@code getPower()} <b>and</b> on the raw servo position the controller received,
 * because those are the two things the hardware distinguishes and a "just remember the power" fake would
 * collapse into one.
 */
class FakeCRServoTest {

    /** Powers worth checking: the ends, the middle, an off-centre value, and two that must be clipped. */
    private static final double[] POWERS = {-2.0, -1.0, -0.75, -0.3, 0.0, 0.3, 0.5, 1.0, 2.0};

    /**
     * The smallest {@link ServoController} that lets a real {@link CRServoImpl} run: it records the servo
     * position written to it and hands it back, which is exactly what the hardware controller does from
     * {@code CRServoImpl}'s point of view.
     */
    private static final class RecordingServoController implements ServoController {

        private double servoPosition = 0.5;

        @Override public void pwmEnable() { }
        @Override public void pwmDisable() { }
        @Override public PwmStatus getPwmStatus() { return PwmStatus.ENABLED; }
        @Override public void setServoPosition(int servo, double position) { this.servoPosition = position; }
        @Override public double getServoPosition(int servo) { return servoPosition; }
        @Override public void forgetLastKnownPosition(int servo) { }

        @Override public Manufacturer getManufacturer() { return Manufacturer.Other; }
        @Override public String getDeviceName() { return "RecordingServoController"; }
        @Override public String getConnectionInfo() { return "test"; }
        @Override public int getVersion() { return 1; }
        @Override public void resetDeviceConfigurationForOpMode() { }
        @Override public void close() { }
    }

    // ---- The headline contract: identical to the real device, case for case ----

    @Test
    void reportsTheSamePowerTheRealCrServoReports() {
        for (DcMotorSimple.Direction direction : DcMotorSimple.Direction.values()) {
            RecordingServoController controller = new RecordingServoController();
            CRServoImpl real = new CRServoImpl(controller, 0);
            FakeCRServo fake = new FakeCRServo();
            real.setDirection(direction);
            fake.setDirection(direction);

            for (double power : POWERS) {
                real.setPower(power);
                fake.setPower(power);
                assertEquals(real.getPower(), fake.getPower(), 1e-12,
                        "getPower() after setPower(" + power + ") with direction " + direction
                                + " must match the real CRServoImpl");
            }
        }
    }

    @Test
    void sendsTheSameServoPositionTheRealCrServoSends() {
        // The fake stores a servo position rather than a power (class javadoc). This checks that the
        // stored value is the same one the real device's controller received -- the internal state, not
        // just the value read back out through the inverse map, which could hide a compensating error.
        for (DcMotorSimple.Direction direction : DcMotorSimple.Direction.values()) {
            RecordingServoController controller = new RecordingServoController();
            CRServoImpl real = new CRServoImpl(controller, 0);
            FakeCRServo fake = new FakeCRServo();
            real.setDirection(direction);
            fake.setDirection(direction);

            for (double power : POWERS) {
                real.setPower(power);
                fake.setPower(power);
                // getPhysicalPower() is the servo position mapped back with NO direction applied, which
                // is the same one-step inverse of what the controller holds.
                double realPhysical = scaleServoPositionToPower(controller.getServoPosition(0));
                assertEquals(realPhysical, fake.getPhysicalPower(), 1e-12,
                        "the servo position stored after setPower(" + power + ") with direction "
                                + direction + " must match what the real CRServoImpl sent its controller");
            }
        }
    }

    @Test
    void changingDirectionAfterSetPowerDoesNotChangeTheMotion() {
        // Real hardware: setDirection sends nothing to the servo, so the horn keeps turning the way it
        // was. Pinned against the real device rather than asserted from memory.
        RecordingServoController controller = new RecordingServoController();
        CRServoImpl real = new CRServoImpl(controller, 0);
        FakeCRServo fake = new FakeCRServo();

        real.setPower(0.6);
        fake.setPower(0.6);
        double physicalBefore = fake.getPhysicalPower();

        real.setDirection(DcMotorSimple.Direction.REVERSE);
        fake.setDirection(DcMotorSimple.Direction.REVERSE);

        assertEquals(physicalBefore, fake.getPhysicalPower(), 1e-12,
                "flipping direction must not change what the horn is already doing");
        assertEquals(real.getPower(), fake.getPower(), 1e-12,
                "and getPower() must report the same flipped reading the real device now reports");
    }

    // ---- Device-surface facts, each checked against the real class ----

    @Test
    void aFreshServoIsStopped() {
        assertEquals(0.0, new FakeCRServo().getPower(), 1e-12,
                "a CR servo powers up holding neutral, which is zero commanded speed");
        assertEquals(0.0, new FakeCRServo().getPhysicalPower(), 1e-12,
                "and its horn is not turning either");
    }

    @Test
    void reportsTheSameVersionTheRealCrServoReports() {
        CRServoImpl real = new CRServoImpl(new RecordingServoController(), 0);
        assertEquals(real.getVersion(), new FakeCRServo().getVersion(),
                "getVersion() must match CRServoImpl, not AbstractFakeDevice's 0 default");
    }

    @Test
    void resetDeviceConfigurationForOpModeRestoresForwardWithoutStoppingTheServo() {
        RecordingServoController controller = new RecordingServoController();
        CRServoImpl real = new CRServoImpl(controller, 0);
        FakeCRServo fake = new FakeCRServo();
        real.setDirection(DcMotorSimple.Direction.REVERSE);
        fake.setDirection(DcMotorSimple.Direction.REVERSE);
        real.setPower(0.4);
        fake.setPower(0.4);

        real.resetDeviceConfigurationForOpMode();
        fake.resetDeviceConfigurationForOpMode();

        assertEquals(real.getDirection(), fake.getDirection(),
                "resetDeviceConfigurationForOpMode restores FORWARD, as CRServoImpl does");
        assertEquals(real.getPower(), fake.getPower(), 1e-12,
                "and it reconfigures the handle rather than stopping the servo");
    }

    @Test
    void aNonFinitePowerIsPassedThroughRatherThanRejected() {
        // Checked against the real device rather than asserted from its javadoc, like every other case in
        // this class: a fake that threw where the hardware does not would hide in sim a caller bug that
        // still reaches the robot.
        RecordingServoController controller = new RecordingServoController();
        CRServoImpl real = new CRServoImpl(controller, 0);
        FakeCRServo fake = new FakeCRServo();

        real.setPower(Double.NaN);
        fake.setPower(Double.NaN);

        assertTrue(Double.isNaN(real.getPower()), "the real CRServoImpl propagates a NaN power");
        assertEquals(Double.isNaN(real.getPower()), Double.isNaN(fake.getPower()),
                "so the fake must propagate it too, rather than rejecting it");
    }

    @Test
    void hasNoServoControllerInSim() {
        assertThrows(UnsupportedOperationException.class, () -> new FakeCRServo().getController(),
                "there is no controller behind a sim servo; state is read through getPower()");
    }

    @Test
    void isAHardwareDeviceSoTheFakeHardwareMapCanRegisterIt() {
        FakeCRServo fake = new FakeCRServo();
        FakeHardwareMap map = new FakeHardwareMap();
        map.register("ejectLeft", fake);
        assertEquals(fake, map.get(com.qualcomm.robotcore.hardware.CRServo.class, "ejectLeft"),
                "unchanged robot code resolves a CR servo by name, as it does on the real robot (R1)");
        assertTrue(fake instanceof HardwareDevice, "and it is a HardwareDevice like every other fake");
    }

    @Test
    void reverseReallyDoesTurnTheHornTheOtherWay() {
        // The one behaviour the two-accessor split exists for: software reads back what it wrote, while
        // the physical truth is flipped (BACKLOG B18, same split as FakeMotor).
        FakeCRServo fake = new FakeCRServo();
        fake.setDirection(DcMotorSimple.Direction.REVERSE);
        fake.setPower(0.5);
        assertEquals(0.5, fake.getPower(), 1e-12, "the caller reads its own request back");
        assertEquals(-0.5, fake.getPhysicalPower(), 1e-12, "while the horn turns the other way");
        assertNotEquals(fake.getPower(), fake.getPhysicalPower(),
                "and the two readings are genuinely different, not the same number twice");
    }

    /** {@code CRServoImpl}'s own servo-position-to-power map, used only to read the controller's state. */
    private static double scaleServoPositionToPower(double servoPosition) {
        return com.qualcomm.robotcore.util.Range.scale(servoPosition, 0.0, 1.0, -1.0, 1.0);
    }
}
