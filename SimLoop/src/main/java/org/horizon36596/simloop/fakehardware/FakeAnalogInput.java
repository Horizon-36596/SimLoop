package org.horizon36596.simloop.fakehardware;

import com.qualcomm.robotcore.hardware.AnalogInput;
import com.qualcomm.robotcore.hardware.HardwareDevice;

/**
 * Fake {@link AnalogInput} returning a settable voltage, so the unchanged SolversLib
 * {@code AbsoluteAnalogEncoder} wrapper (which reads {@code getVoltage()} / {@code getMaxVoltage()})
 * resolves headless. {@code AnalogInput} is a concrete SDK class, so we subclass it and pass a
 * {@code null} controller — EVERY inherited method that would touch the controller (the reads plus the
 * {@code HardwareDevice} metadata methods PsiKit logs, e.g. {@code getConnectionInfo}) is overridden, so
 * the null controller is never dereferenced (fidelity rule, plant §0). Deterministic; voltage settable.
 */
public class FakeAnalogInput extends AnalogInput implements FakeDevice {

    private double voltage;
    private final double maxVoltage;

    /** Creates an input reading 0.0 V on a 3.3 V range, the common FTC analog sensor wiring. */
    public FakeAnalogInput() {
        this(0.0, 3.3);
    }

    /**
     * Creates an input reading a chosen voltage on a chosen range.
     *
     * @param voltage    the voltage this input reports, in volts
     * @param maxVoltage the full-scale voltage of the range, in volts
     */
    public FakeAnalogInput(double voltage, double maxVoltage) {
        super(null, 0);
        this.voltage = voltage;
        this.maxVoltage = maxVoltage;
    }

    /**
     * Sets the voltage this input reports from now on.
     *
     * @param voltage the reading, in volts
     */
    public void setVoltage(double voltage) {
        this.voltage = voltage;
    }

    @Override
    public void update(double deltaTime) {
        // Constant source; no dynamics.
    }

    /** {@return the reading, in volts} */
    @Override
    public double getVoltage() {
        return voltage;
    }

    /** {@return the full-scale voltage of the range, in volts} */
    @Override
    public double getMaxVoltage() {
        return maxVoltage;
    }

    // Controller-backed / metadata methods overridden so the null controller is never dereferenced
    // (PsiKit's HardwareMapWrapper reads getConnectionInfo/getDeviceName when wrapping devices).
    @Override public String getDeviceName() { return "FakeAnalogInput"; }
    @Override public String getConnectionInfo() { return "sim"; }
    @Override public HardwareDevice.Manufacturer getManufacturer() { return HardwareDevice.Manufacturer.Other; }
    @Override public int getVersion() { return 0; }
    @Override public void resetDeviceConfigurationForOpMode() { }
    @Override public void close() { }
}
