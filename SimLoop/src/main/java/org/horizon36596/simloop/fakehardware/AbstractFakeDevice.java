package org.horizon36596.simloop.fakehardware;

import com.qualcomm.robotcore.hardware.HardwareDevice;

/**
 * Base for fake devices: supplies the boilerplate {@link HardwareDevice} metadata so concrete fakes
 * (e.g. {@link FakeMotor}) only implement their device interface + the plant {@link #update(double)}.
 * Seeded from PsiKit's test {@code FakeHardware} (fakehardware-plant §1).
 */
public abstract class AbstractFakeDevice implements HardwareDevice, FakeDevice {

    /**
     * Creates the device. Holds no state of its own; every subclass owns whatever it simulates.
     *
     * <p>Public rather than protected because that is what the implicit constructor this replaces was:
     * the default constructor of a {@code public} class is {@code public} (JLS 8.8.9), so narrowing it
     * would quietly remove an anonymous subclass in another package - an API break, in a batch that is
     * only meant to be adding comments.
     */
    public AbstractFakeDevice() {
    }

    @Override public Manufacturer getManufacturer() { return Manufacturer.Other; }
    @Override public String getDeviceName() { return getClass().getSimpleName(); }
    @Override public String getConnectionInfo() { return "sim"; }
    @Override public int getVersion() { return 0; }
    @Override public void resetDeviceConfigurationForOpMode() { }
    @Override public void close() { }
}
