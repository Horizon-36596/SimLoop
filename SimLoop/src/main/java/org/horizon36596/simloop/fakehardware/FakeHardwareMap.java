package org.horizon36596.simloop.fakehardware;

import com.qualcomm.robotcore.hardware.AnalogInput;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DigitalChannel;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.VoltageSensor;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM-constructible {@link HardwareMap} that resolves fake devices by config name
 * (fakehardware-plant §6). Devices are registered under the SAME names the real robot config uses,
 * so the same subsystem code resolves them in sim and on the robot (domain R1).
 *
 * <p>Subclassing the SDK {@code HardwareMap} with a null context is the JVM seam PsiKit's own test
 * {@code JVMHardwareMap} relies on. The harness ticks the plant via {@link #updateAll(double)}.
 *
 * <p><b>Registration goes through the SDK's own storage, not a map of our own.</b> This class used to keep
 * a private {@code Map<String, HardwareDevice>} and override {@code get} to read it. That made the fake map
 * detectable by any robot code that used the rest of the {@link HardwareMap} surface: {@code tryGet} returned
 * null, {@code getAll(DcMotor.class)} came back empty, iterating the map yielded nothing, {@code size()} was
 * 0, and the typed mappings such as {@code hardwareMap.dcMotor} held nothing — all while {@code get} worked.
 * A private map also silently dropped the SDK's own naming rules: it did not trim the name, and registering
 * two devices of different types under one name overwrote the first instead of keeping both. Registering
 * through {@link DeviceMapping#put} gets every one of those behaviours from the SDK rather than
 * re-implementing them, which is what the fidelity rule asks for. (Found by the 2026-09-17 adversarial
 * review, findings {@code hardware-map-split-state} and {@code hardware-map-name-semantics}.)
 */
public class FakeHardwareMap extends HardwareMap {

    /**
     * Registration order, kept only so {@link #updateAll(double)} ticks plants in a fixed sequence. The
     * SDK's own storage is unordered, and a sim whose plants update in hash order is not deterministic
     * (domain R5). This list is never read as a lookup table — that is what the inherited map is for.
     */
    private final List<FakeDevice> devicesInRegistrationOrder = new ArrayList<>();

    /** Creates an empty map; register each fake device with {@link #register(String, HardwareDevice)}. */
    public FakeHardwareMap() {
        super(null, null);
    }

    /**
     * Registers a fake device (which is both an SDK {@link HardwareDevice} and a {@link FakeDevice}) under
     * {@code name}, exactly as the real robot-configuration parser registers the hardware it replaces.
     *
     * @param <T>    the device's type, which must be both an SDK device and a tickable fake
     * @param name   the config name the robot code will look the device up by, matching the robot's real
     *               configuration file; unitless, and leading and trailing whitespace is trimmed by the
     *               SDK exactly as it is on a real Control Hub
     * @param device the fake to return under that name, and to tick on {@link #updateAll(double)}
     */
    public <T extends HardwareDevice & FakeDevice> void register(String name, T device) {
        putIntoTheMatchingTypedMapping(name, device);
        devicesInRegistrationOrder.add(device);
    }

    /**
     * Files the device into the type-specific {@link DeviceMapping} the real SDK would have used, so
     * {@code hardwareMap.dcMotor}, {@code hardwareMap.servo} and friends contain what a robot expects.
     * {@code DeviceMapping.put} also writes the overall map, so one call covers both.
     *
     * <p>The chain is written out device type by device type rather than derived by reflection because a
     * reader has to be able to see which mapping a fake lands in without running it. A device whose type
     * has no mapping here — {@code FakeI2cDeviceSynchSimple} is the current example, since the SDK's
     * {@code i2cDeviceSynch} mapping is typed to the richer {@code I2cDeviceSynch} — still goes into the
     * overall map, which is exactly what the SDK's own {@link HardwareMap#put(String, HardwareDevice)}
     * is for.
     *
     * <p><b>First match wins, and one case relies on that.</b> {@code FakeExternalEncoder} implements
     * {@code DcMotorEx}, so it takes the {@code DcMotor} branch and lands in {@code hardwareMap.dcMotor}
     * alongside {@code FakeMotor}. That is right rather than a collision: an external encoder is plugged
     * into a motor port and is configured on a real robot as a motor, so a real {@code HardwareMap} holds
     * it in the same mapping. No other fake in this package matches two branches today; if one is added
     * that does, the order of this chain becomes a decision rather than an accident, and it should be
     * stated here at that point.
     */
    private void putIntoTheMatchingTypedMapping(String name, HardwareDevice device) {
        if (device instanceof DcMotor) {
            dcMotor.put(name, (DcMotor) device);
        } else if (device instanceof CRServo) {
            crservo.put(name, (CRServo) device);
        } else if (device instanceof Servo) {
            servo.put(name, (Servo) device);
        } else if (device instanceof DigitalChannel) {
            digitalChannel.put(name, (DigitalChannel) device);
        } else if (device instanceof AnalogInput) {
            analogInput.put(name, (AnalogInput) device);
        } else if (device instanceof VoltageSensor) {
            voltageSensor.put(name, (VoltageSensor) device);
        } else {
            put(name, device);
        }
    }

    /**
     * The SDK's own {@code tryGet}, minus the one line of it that cannot run off a robot.
     *
     * <p>{@code HardwareMap.tryGet} ends with a diagnostic that warns a team who asked for a BNO055 IMU on a
     * Control Hub that has a BHI260 — and it reaches that diagnostic through {@code Device.isRevControlHub()},
     * whose class initializer calls {@code android.os.Environment.getExternalStorageDirectory()}. On a JVM
     * that throws {@code "Method getExternalStorageDirectory in android.os.Environment not mocked"}, so the
     * inherited lookup cannot be used here at all: every {@code get} in every sim test would die inside the
     * SDK before it found anything. Overriding this one method is what lets everything else on
     * {@link HardwareMap} — {@code get}, {@code getAll}, {@code size}, iteration, the typed mappings — be the
     * SDK's own code reading the SDK's own storage.
     *
     * <p>The lookup itself is the SDK's, line for line: trim the name, walk the devices registered under it,
     * return the first one that is an instance of the requested type. The two things left out are the
     * Control-Hub IMU warning above, and {@code initializeDeviceIfNecessary}, which is private and only ever
     * does anything for an {@code I2cDeviceSynchDevice} — no fake in this package is one.
     *
     * @param classOrInterface the type the caller wants back
     * @param deviceName       the config name, trimmed here exactly as the SDK trims it
     * @return the first registered device under that name that is an instance of {@code classOrInterface},
     *         or null if there is none — the SDK's contract for this method
     */
    @Override
    public <T> T tryGet(Class<? extends T> classOrInterface, String deviceName) {
        List<HardwareDevice> registeredUnderThatName = allDevicesMap.get(deviceName.trim());
        if (registeredUnderThatName == null) {
            return null;
        }
        for (HardwareDevice device : registeredUnderThatName) {
            if (classOrInterface.isInstance(device)) {
                return classOrInterface.cast(device);
            }
        }
        return null;
    }

    /**
     * Advances every registered fake device's plant by one harness tick.
     *
     * @param deltaTime how far to advance, in seconds; the harness passes its fixed tick
     */
    public void updateAll(double deltaTime) {
        for (FakeDevice device : devicesInRegistrationOrder) {
            device.update(deltaTime);
        }
    }
}
