package org.horizon36596.simloop.fakehardware;

import com.qualcomm.robotcore.hardware.I2cAddr;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple;
import com.qualcomm.robotcore.hardware.I2cWaitControl;
import com.qualcomm.robotcore.hardware.TimestampedData;

/**
 * Fake {@link I2cDeviceSynchSimple} (the I2C bus client) that an {@code I2cDeviceSynchDevice} subclass
 * holds as its {@code deviceClient}. It exists so a concrete I2C device driver (e.g. an OctoQuad fake)
 * can be CONSTRUCTED headless: the driver's constructor configures the bus client
 * ({@code enableWriteCoalescing}, {@code setI2cAddress}) before any transaction, and this models exactly
 * that bus-configuration surface.
 *
 * <p>Fidelity (fakehardware-plant §0): bus-config calls are modelled (stored/no-op); the data-transfer
 * methods ({@code read*}/{@code write*}/{@code readTimeStamped}/{@code waitForWriteCompletions}) FAIL LOUD,
 * because there is no real I2C peripheral behind the bus to model. A device fake is expected to OVERRIDE
 * the driver's public API so it never reaches the bus; if it does reach the bus, that is a real gap and
 * the loud failure surfaces it rather than a fake peripheral silently returning plausible-but-wrong bytes.
 *
 * <p>Season-agnostic: depends only on FTC SDK hardware interfaces (no SolversLib, no teamcode) — it
 * travels with the SimLoop module (SimLoop/CLAUDE.md rule 1). Not a registered {@link FakeDevice}: it is
 * the bus client wrapped INSIDE a device, never put in {@code FakeHardwareMap} directly.
 */
public class FakeI2cDeviceSynchSimple implements I2cDeviceSynchSimple {

    /** Creates a bus client at I2C address zero, with write coalescing and logging off. */
    public FakeI2cDeviceSynchSimple() {
    }

    private I2cAddr i2cAddr = I2cAddr.zero();
    private boolean writeCoalescingEnabled;
    private boolean logging;
    private String loggingTag = "FakeI2cDeviceSynchSimple";
    private String userConfiguredName;
    private HealthStatus healthStatus = HealthStatus.HEALTHY;

    private RuntimeException noPeripheral() {
        return new UnsupportedOperationException(
                "FakeI2cDeviceSynchSimple models only bus configuration, not data transfer — there is no "
                        + "I2C peripheral behind it. The device fake must override the driver methods it uses "
                        + "so it never reaches the bus.");
    }

    // ---- Bus configuration (modelled — these are what the device ctor calls) ----
    @Override public void enableWriteCoalescing(boolean enable) { this.writeCoalescingEnabled = enable; }
    @Override public boolean isWriteCoalescingEnabled() { return writeCoalescingEnabled; }
    @Override public boolean isArmed() { return false; } // never armed against real USB in sim

    @Override public void setI2cAddress(I2cAddr newAddress) { this.i2cAddr = newAddress; }
    @Override public I2cAddr getI2cAddress() { return i2cAddr; }
    @Override public void setI2cAddr(I2cAddr newAddress) { this.i2cAddr = newAddress; }
    @Override public I2cAddr getI2cAddr() { return i2cAddr; }

    @Override public void setLogging(boolean logging) { this.logging = logging; }
    @Override public boolean getLogging() { return logging; }
    @Override public void setLoggingTag(String loggingTag) { this.loggingTag = loggingTag; }
    @Override public String getLoggingTag() { return loggingTag; }

    // ---- Data transfer (fail loud — no peripheral to model, §0) ----
    @Override public byte read8() { throw noPeripheral(); }
    @Override public byte read8(int ireg) { throw noPeripheral(); }
    @Override public byte[] read(int creg) { throw noPeripheral(); }
    @Override public byte[] read(int ireg, int creg) { throw noPeripheral(); }
    @Override public TimestampedData readTimeStamped(int creg) { throw noPeripheral(); }
    @Override public TimestampedData readTimeStamped(int ireg, int creg) { throw noPeripheral(); }
    @Override public void write8(int bVal) { throw noPeripheral(); }
    @Override public void write8(int ireg, int bVal) { throw noPeripheral(); }
    @Override public void write8(int bVal, I2cWaitControl waitControl) { throw noPeripheral(); }
    @Override public void write8(int ireg, int bVal, I2cWaitControl waitControl) { throw noPeripheral(); }
    @Override public void write(byte[] data) { throw noPeripheral(); }
    @Override public void write(int ireg, byte[] data) { throw noPeripheral(); }
    @Override public void write(byte[] data, I2cWaitControl waitControl) { throw noPeripheral(); }
    @Override public void write(int ireg, byte[] data, I2cWaitControl waitControl) { throw noPeripheral(); }
    @Override public void waitForWriteCompletions(I2cWaitControl waitControl) { throw noPeripheral(); }

    // ---- HardwareDeviceHealth ----
    @Override public void setHealthStatus(HealthStatus status) { this.healthStatus = status; }
    @Override public HealthStatus getHealthStatus() { return healthStatus; }

    // ---- RobotConfigNameable ----
    @Override public void setUserConfiguredName(String name) { this.userConfiguredName = name; }
    @Override public String getUserConfiguredName() { return userConfiguredName; }

    // ---- HardwareDevice ----
    @Override public Manufacturer getManufacturer() { return Manufacturer.Other; }
    @Override public String getDeviceName() { return "FakeI2cDeviceSynchSimple"; }
    @Override public String getConnectionInfo() { return "sim"; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() { }
    @Override public void close() { }
}
