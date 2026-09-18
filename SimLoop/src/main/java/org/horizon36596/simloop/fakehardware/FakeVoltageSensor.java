package org.horizon36596.simloop.fakehardware;

import com.qualcomm.robotcore.hardware.VoltageSensor;

/**
 * Fake {@link VoltageSensor} returning a fixed nominal battery voltage (default 12 V). Lets
 * {@code Robot.getVoltage()} and any voltage-compensated control resolve headless. Deterministic;
 * the held voltage is settable for tests that want brown-out behavior.
 */
public class FakeVoltageSensor extends AbstractFakeDevice implements VoltageSensor {

    private double voltage;

    /** Creates a sensor holding the nominal 12.0 volts. */
    public FakeVoltageSensor() {
        this(12.0);
    }

    /**
     * Creates a sensor holding a chosen voltage.
     *
     * @param voltage the battery voltage to report, in volts
     */
    public FakeVoltageSensor(double voltage) {
        this.voltage = voltage;
    }

    /**
     * Sets the voltage this sensor reports from now on, for a test that wants brown-out behaviour.
     *
     * @param voltage the battery voltage to report, in volts
     */
    public void setVoltage(double voltage) {
        this.voltage = voltage;
    }

    @Override
    public void update(double deltaTime) {
        // Constant source; no dynamics.
    }

    /** {@return the battery voltage this sensor reports, in volts} */
    @Override
    public double getVoltage() {
        return voltage;
    }
}
