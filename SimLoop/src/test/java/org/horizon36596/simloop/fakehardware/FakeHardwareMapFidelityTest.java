package org.horizon36596.simloop.fakehardware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.Servo;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Pins that {@link FakeHardwareMap} is one map rather than two.
 *
 * <p>Until 2026-09-17 it kept its registrations in a private map and overrode {@code get} to read that,
 * which left every other part of the inherited {@link com.qualcomm.robotcore.hardware.HardwareMap} surface
 * empty: {@code tryGet} returned null for a device {@code get} had just returned, {@code getAll} and the
 * iterator were empty, {@code size()} was 0, and {@code hardwareMap.dcMotor} held nothing. Robot code could
 * tell it was in sim by asking any of those, which is exactly what the fakes exist to prevent. Found by that
 * day's adversarial review (findings hardware-map-split-state and hardware-map-name-semantics).
 */
class FakeHardwareMapFidelityTest {

    private static FakeMotor aMotor() {
        return new FakeMotor(1000.0, 10.0);
    }

    @Test
    void aRegisteredDeviceIsVisibleThroughEveryLookupTheSdkOffers() {
        FakeHardwareMap map = new FakeHardwareMap();
        FakeMotor motor = aMotor();
        map.register("frontLeft", motor);

        assertSame(motor, map.get(DcMotor.class, "frontLeft"), "the typed get");
        assertSame(motor, map.tryGet(DcMotor.class, "frontLeft"), "tryGet, which used to return null");
        assertSame(motor, map.get("frontLeft"), "the untyped get");
        assertEquals(List.of(motor), map.getAll(DcMotor.class), "getAll, which used to be empty");
        assertEquals(1, map.size(), "size, which used to be 0");
        assertEquals(List.of((HardwareDevice) motor), map.getAll(HardwareDevice.class),
                "whole-map enumeration, which used to yield nothing");

        // HardwareMap.iterator() is not exercised here, and that is a limit of the JVM rather than of this
        // fake: the SDK's iterator() opens by logging a deprecation warning through RobotLog, which calls
        // android.util.Log.println, which is not mocked off a device. It reads the same allDevicesMap that
        // getAll above reads, so there is nothing left for it to disagree with.
    }

    @Test
    void aDeviceLandsInTheTypedMappingRobotCodeReadsItFrom() {
        FakeHardwareMap map = new FakeHardwareMap();
        FakeMotor motor = aMotor();
        FakeServo servo = new FakeServo();
        map.register("lift", motor);
        map.register("claw", servo);

        assertSame(motor, map.dcMotor.get("lift"), "hardwareMap.dcMotor is where a motor belongs");
        assertSame(servo, map.servo.get("claw"), "hardwareMap.servo is where a servo belongs");
        assertTrue(map.dcMotor.size() == 1 && map.servo.size() == 1,
                "each typed mapping holds exactly the devices of its own type");
    }

    @Test
    void namesAreTrimmedTheWayTheSdkTrimsThem() {
        FakeHardwareMap map = new FakeHardwareMap();
        FakeMotor motor = aMotor();
        map.register("  frontLeft  ", motor);

        // HardwareMap.internalPut trims on the way in and get/tryGet trim on the way out, so a stray space
        // in a config name resolves on a real robot. It used to throw here.
        assertSame(motor, map.get(DcMotor.class, "frontLeft"), "trimmed on registration");
        assertSame(motor, map.get(DcMotor.class, " frontLeft "), "and trimmed on lookup");
    }

    @Test
    void twoDevicesOfDifferentTypesCanShareOneConfigName() {
        FakeHardwareMap map = new FakeHardwareMap();
        FakeMotor motor = aMotor();
        FakeServo servo = new FakeServo();
        map.register("shared", motor);
        map.register("shared", servo);

        // The SDK keeps a LIST per name and resolves by the requested type. The old private map was keyed by
        // name alone, so registering the servo silently dropped the motor.
        assertSame(motor, map.get(DcMotor.class, "shared"), "the motor survives the second registration");
        assertSame(servo, map.get(Servo.class, "shared"), "and the servo is there too");
    }

    @Test
    void anUnregisteredNameResolvesToNullFromTryGetRatherThanThrowing() {
        FakeHardwareMap map = new FakeHardwareMap();

        assertNull(map.tryGet(DcMotor.class, "nothingIsCalledThis"),
                "tryGet's contract is null, not an exception — that is what tells it apart from get");
    }

    @Test
    void aDeviceOfTheWrongTypeIsNotReturnedUnderItsName() {
        FakeHardwareMap map = new FakeHardwareMap();
        map.register("claw", new FakeServo());

        assertNull(map.tryGet(DcMotor.class, "claw"), "a servo is not a motor, whatever it is called");
        assertNotNull(map.tryGet(Servo.class, "claw"));
    }

    @Test
    void everyRegisteredDeviceIsStillTickedByUpdateAll() {
        FakeHardwareMap map = new FakeHardwareMap();
        FakeMotor motor = aMotor();
        map.register("lift", motor);
        motor.setPower(1.0);

        map.updateAll(0.02);

        assertTrue(motor.getSpeed() > 0.0, "updateAll still advances the plants it always did");
    }
}
