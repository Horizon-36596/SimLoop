package org.horizon36596.simloop.fakehardware;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qualcomm.robotcore.hardware.Servo;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.junit.jupiter.api.Test;

/**
 * INVARIANT-tier (domain R9) — the interface-based device fakes behave like their SDK contract so the
 * unchanged subsystem/wrapper code can drive them headless (R1). Implementation-independent: any fake
 * for these SDK interfaces must hold these.
 */
class FakeHardwareDevicesTest {

    // ---- FakeMotor (DcMotorEx upgrade) ----

    @Test
    void motorVelocityIsAPositiveFractionOfMaxAfterSpinUp() {
        // getVelocity() (ticks/s) is what MotorEx reads via the (DcMotorEx) cast. Pin that it is wired
        // to the model (positive, bounded by max) — NOT == getVelocityTicksPerSecond() (that's circular).
        FakeMotor motor = new FakeMotor(1000.0, 2.0);
        motor.setPower(1.0);
        for (int i = 0; i < 100; i++) motor.update(0.02); // spin up
        double velocity = motor.getVelocity();
        assertTrue(velocity > 0 && velocity <= 1000.0,
                "spun-up velocity is a positive fraction of max (ticks/s), not hardcoded");
    }

    @Test
    void motorCurrentIsModelledAsNeverOverCurrent() {
        FakeMotor motor = new FakeMotor(1000.0, 2.0);
        motor.setCurrentAlert(5.0, CurrentUnit.AMPS);
        assertEquals(5.0, motor.getCurrentAlert(CurrentUnit.AMPS), 1e-9);
        assertEquals(0.0, motor.getCurrent(CurrentUnit.AMPS), 1e-9);
        assertFalse(motor.isOverCurrent(), "kinematic motor never trips over-current");
    }

    @Test
    void motorAngularVelocityConvertsTicksViaCpr() {
        double cpr = 500.0;
        FakeMotor motor = new FakeMotor(1000.0, 2.0, cpr);
        motor.setPower(1.0);
        for (int i = 0; i < 200; i++) motor.update(0.02); // spin up
        double ticksPerSec = motor.getVelocityTicksPerSecond();
        assertTrue(ticksPerSec > 0, "precondition: spun up");
        // deg/s must be ticks/s scaled by (360 / CPR) — the real SDK conversion, NOT a hardcoded 0.
        assertEquals(360.0 / cpr, motor.getVelocity(AngleUnit.DEGREES) / ticksPerSec, 1e-9);
        // and radians is just the degree value converted — unit consistency.
        assertEquals(Math.toRadians(motor.getVelocity(AngleUnit.DEGREES)),
                motor.getVelocity(AngleUnit.RADIANS), 1e-9);
    }

    // ---- FakeServo ----

    @Test
    void servoHoldsCommandedPositionClampedToUnitRange() {
        FakeServo servo = new FakeServo();
        servo.setPosition(0.7);
        assertEquals(0.7, servo.getPosition(), 1e-9);
        servo.setPosition(1.5);
        assertEquals(1.0, servo.getPosition(), 1e-9, "position clamps to 1.0");
        servo.setPosition(-0.5);
        assertEquals(0.0, servo.getPosition(), 1e-9, "position clamps to 0.0");
    }

    @Test
    void servoRetainsDirection() {
        FakeServo servo = new FakeServo();
        servo.setDirection(Servo.Direction.REVERSE);
        assertEquals(Servo.Direction.REVERSE, servo.getDirection());
    }

    @Test
    void servoScaleRangeMapsPhysicalOutputLikeSdk() {
        FakeServo servo = new FakeServo();
        servo.scaleRange(0.2, 0.8); // restrict physical travel to [0.2, 0.8]
        // logical [0,1] maps onto the physical window; getScaledPosition() is the physical output.
        servo.setPosition(0.0);
        assertEquals(0.2, servo.getScaledPosition(), 1e-9);
        servo.setPosition(1.0);
        assertEquals(0.8, servo.getScaledPosition(), 1e-9);
        servo.setPosition(0.5);
        assertEquals(0.5, servo.getScaledPosition(), 1e-9);
        assertEquals(0.5, servo.getPosition(), 1e-9, "logical position round-trips through the scaling");
        // An inverted range is the one thing ServoImpl.scaleRange rejects.
        assertThrows(IllegalArgumentException.class, () -> servo.scaleRange(0.8, 0.2));
    }

    @Test
    void servoScaleRangeClipsOutOfRangeEndpointsRatherThanRejectingThem() {
        // ServoImpl.scaleRange clips each endpoint into [0, 1] FIRST and only then checks min < max, so an
        // endpoint outside [0, 1] is a legal request on real hardware. These two assertions used to expect
        // an IllegalArgumentException, which made the fake stricter than the device it stands in for: a
        // call that works on the robot went red in sim. (2026-09-17 adversarial review, finding
        // servo-scale-range-clipping.)
        FakeServo clippedLow = new FakeServo();
        clippedLow.scaleRange(-0.1, 0.5);
        clippedLow.setPosition(0.0);
        assertEquals(0.0, clippedLow.getScaledPosition(), 1e-9, "-0.1 clips to the 0.0 endpoint");
        clippedLow.setPosition(1.0);
        assertEquals(0.5, clippedLow.getScaledPosition(), 1e-9, "the upper endpoint is untouched");

        FakeServo clippedHigh = new FakeServo();
        clippedHigh.scaleRange(0.5, 1.1);
        clippedHigh.setPosition(0.0);
        assertEquals(0.5, clippedHigh.getScaledPosition(), 1e-9, "the lower endpoint is untouched");
        clippedHigh.setPosition(1.0);
        assertEquals(1.0, clippedHigh.getScaledPosition(), 1e-9, "1.1 clips to the 1.0 endpoint");
    }

    @Test
    void servoDirectionReversesThePhysicalOutputTheWayServoImplDoes() {
        // ServoImpl.setPosition clips, then reverses, THEN scales. The order is what makes these numbers
        // what they are: reversing after scaling, or not at all, gives 0.28 below instead of 0.52.
        FakeServo reversed = new FakeServo();
        reversed.setDirection(Servo.Direction.REVERSE);
        reversed.setPosition(0.2);
        assertEquals(0.8, reversed.getScaledPosition(), 1e-9,
                "a reversed servo commanded 0.2 drives its horn to the mirrored 0.8");
        assertEquals(0.2, reversed.getPosition(), 1e-9,
                "the logical position still reads back as commanded: getPosition un-reverses it");

        FakeServo reversedAndScaled = new FakeServo();
        reversedAndScaled.setDirection(Servo.Direction.REVERSE);
        reversedAndScaled.scaleRange(0.2, 0.6);
        reversedAndScaled.setPosition(0.2);
        assertEquals(0.52, reversedAndScaled.getScaledPosition(), 1e-9,
                "reverse is applied BEFORE the scaling window: 0.2 + 0.8 * 0.4");

        FakeServo forward = new FakeServo();
        forward.setPosition(0.2);
        assertEquals(0.2, forward.getScaledPosition(), 1e-9, "a FORWARD servo is unaffected");
    }

    // ---- FakeAnalogInput ----

    @Test
    void analogInputReturnsSettableVoltage() {
        FakeAnalogInput analog = new FakeAnalogInput(1.5, 3.3);
        assertEquals(1.5, analog.getVoltage(), 1e-9);
        assertEquals(3.3, analog.getMaxVoltage(), 1e-9);
        analog.setVoltage(2.0);
        assertEquals(2.0, analog.getVoltage(), 1e-9);
    }

    @Test
    void analogInputMetadataIsControllerSafe() {
        // super(null controller): inherited metadata reads (which PsiKit logs) must not deref it.
        FakeAnalogInput analog = new FakeAnalogInput();
        assertDoesNotThrow(analog::getConnectionInfo);
        assertDoesNotThrow(analog::getDeviceName);
        assertDoesNotThrow(analog::getManufacturer);
    }

    // ---- FakeVoltageSensor ----

    @Test
    void voltageSensorReturnsNominalThenSettableVoltage() {
        FakeVoltageSensor sensor = new FakeVoltageSensor();
        assertEquals(12.0, sensor.getVoltage(), 1e-9, "defaults to 12 V nominal");
        sensor.setVoltage(7.4);
        assertEquals(7.4, sensor.getVoltage(), 1e-9);
    }
}
