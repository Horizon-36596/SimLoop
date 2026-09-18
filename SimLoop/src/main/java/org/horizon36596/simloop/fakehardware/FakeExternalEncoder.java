package org.horizon36596.simloop.fakehardware;

import com.qualcomm.robotcore.hardware.DcMotorController;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.PIDCoefficients;
import com.qualcomm.robotcore.hardware.PIDFCoefficients;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;

/**
 * Fake external encoder (fakehardware-plant §4): the plant &rarr; sensor &rarr; subsystem seam for a 1-DOF
 * mechanism. A mechanism's subsystem always closes its own loop on an <b>external encoder</b> that measures
 * the true carriage/joint position (never RUN_TO_POSITION); this fake models that encoder. Its count is
 * <b>injected</b> each tick with the mechanism's simulated position from a {@link org.horizon36596.simloop.plant.Mechanism1DofPlant}
 * (via {@link #setEncoderPosition(double)}) — NOT integrated from a commanded power. That is the whole point
 * of a dedicated device: the actuating {@link FakeMotor}s integrate their own (end-limit-unaware) position,
 * so the subsystem must read the plant's clamped position through a separate sensor, exactly as on hardware
 * where the encoder reports the real shaft (domain R1).
 *
 * <p>It presents the full {@link DcMotorEx} surface (so SolversLib's {@code Motor}/{@code Motor.Encoder}
 * wrapper resolves and reads it unchanged, like a through-bore encoder wired to a motor-encoder port), but
 * it is a <b>sensor</b>: {@code setPower} is stored for API fidelity yet drives nothing — the shaft position
 * comes only from injection. Velocity is derived by finite-differencing the injected position across
 * {@link #update(double)} ticks (mirroring how the SDK derives encoder velocity from counts over time), so
 * it never reads the plant's modelled velocity as a feedback sensor.
 *
 * <p>Season-agnostic core: depends only on the SDK {@link DcMotorEx} surface (SimLoop rule 1/2, domain R7).
 * Deterministic: position changes only via injection and velocity only in {@link #update(double)} off the
 * injected tick {@code deltaTime}, never a wall-clock (domain R5); getters are pure reads (conventions §4).
 * Fidelity (fakehardware-plant §0): every {@link DcMotorEx} method is modelled or fails loud — none returns
 * a plausible-but-wrong value.
 */
public class FakeExternalEncoder extends AbstractFakeDevice implements DcMotorEx {

    private final double motorTypeMaxVelocityTicksPerSecond;
    private final double ticksPerRev;

    // Injected truth (the plant's clamped position, in encoder ticks) + finite-differenced velocity.
    private double positionTicks = 0.0;
    private double prevPositionTicks = 0.0;
    private double velocityTicksPerSecond = 0.0;

    private double power = 0.0;
    private Direction direction = Direction.FORWARD;
    private ZeroPowerBehavior zeroPowerBehavior = ZeroPowerBehavior.FLOAT;
    private double currentAlert = 0.0;
    private boolean motorEnabled = true;

    /**
     * Builds an encoder reading zero ticks at zero velocity.
     *
     * @param motorTypeMaxVelocityTicksPerSecond the mechanism's free-speed shaft rate (ticks/s). This is an
     *                                    actuator concept the SolversLib {@code Motor} ctor reads via
     *                                    {@link #getMotorType()} — it exists ONLY to keep that type coherent;
     *                                    a sensor never senses with it and the RawPower loop never touches it.
     * @param ticksPerRev                 encoder counts per output-shaft revolution — the real device CPR,
     *                                    so {@code Motor.getCPR()} and {@link #getVelocity(AngleUnit)} read
     *                                    true.
     */
    public FakeExternalEncoder(double motorTypeMaxVelocityTicksPerSecond, double ticksPerRev) {
        if (ticksPerRev <= 0) {
            throw new IllegalArgumentException("ticksPerRev must be positive, got " + ticksPerRev);
        }
        this.motorTypeMaxVelocityTicksPerSecond = motorTypeMaxVelocityTicksPerSecond;
        this.ticksPerRev = ticksPerRev;
    }

    /**
     * Inject the mechanism's current position (in encoder ticks) — the harness calls this each tick with
     * {@code plant.getPosition() * ticksPerUnit}. Reads immediately (position is not integrated); the next
     * {@link #update(double)} finite-differences it into a velocity.
     *
     * @param ticks the count to report from now on, in encoder ticks
     * @throws IllegalArgumentException if {@code ticks} is non-finite (a caller bug that would poison the
     *                                  count the subsystem closes its loop on, fakehardware-plant §0).
     */
    public void setEncoderPosition(double ticks) {
        if (!Double.isFinite(ticks)) {
            throw new IllegalArgumentException("injected encoder position must be finite, got " + ticks);
        }
        this.positionTicks = ticks;
    }

    @Override
    public void update(double deltaTime) {
        // A non-finite or non-positive deltaTime is not a real sim tick (FakeTimer is monotonic, domain R5);
        // treat it as no time passing so a bad dt cannot inject a NaN/±Inf velocity.
        if (!Double.isFinite(deltaTime) || deltaTime <= 0.0) {
            return;
        }
        // Derive velocity from the change in injected position over the tick (how the SDK gets encoder
        // velocity from counts) rather than reading the plant's modelled velocity as a feedback sensor.
        velocityTicksPerSecond = (positionTicks - prevPositionTicks) / deltaTime;
        prevPositionTicks = positionTicks;
    }

    // ---- DcMotorSimple ----
    @Override public Direction getDirection() { return direction; }
    @Override public void setDirection(Direction direction) { this.direction = direction; }
    @Override public double getPower() { return power; }
    // Stored for API fidelity, but a sensor drives nothing: position comes only from injection.
    @Override public void setPower(double power) { this.power = Math.max(-1.0, Math.min(1.0, power)); }

    // ---- DcMotor ----
    @Override public ZeroPowerBehavior getZeroPowerBehavior() { return zeroPowerBehavior; }
    @Override public void setZeroPowerBehavior(ZeroPowerBehavior behavior) { this.zeroPowerBehavior = behavior; }
    @Override public int getCurrentPosition() {
        // A REVERSE device reports negated counts (SDK contract); FORWARD in every current wiring.
        double signed = (direction == Direction.REVERSE) ? -positionTicks : positionTicks;
        return (int) signed;
    }

    // ---- Unsupported / no-op stubs: mirror FakeMotor — fail loud where a real read would be expected
    //      (conventions §4), no-op where the SDK interface only needs a stub for the sim path. ----
    @Override @Deprecated public void setPowerFloat() { /* no-op: use setPower(0) + FLOAT behavior */ }
    @Override public boolean getPowerFloat() { return false; }
    @Override public void setTargetPosition(int position) { /* RUN_TO_POSITION not modelled (external-encoder loop) */ }
    @Override public int getTargetPosition() { return 0; }
    @Override public boolean isBusy() { return false; }
    @Override public void setMode(RunMode mode) { /* mode has no effect: position is injected */ }
    @Override public RunMode getMode() { return RunMode.RUN_WITHOUT_ENCODER; }
    @Override public MotorConfigurationType getMotorType() {
        // Fidelity (plant §0): SolversLib Motor.getCPR() reads getMotorType().getTicksPerRev() and the Motor
        // ctor reads getAchieveableMaxTicksPerSecond(); an empty type returns 0 for both. Populate a coherent
        // type (same recipe as FakeMotor): ticksPerRev is the real CPR, maxRPM/fraction chosen so
        // getAchieveableMaxTicksPerSecond() == maxVelocityInTicksPerSecond. Fresh instance per call; SolversLib
        // clones it before mutating, so callers never corrupt this fake's state.
        MotorConfigurationType type = new MotorConfigurationType();
        type.setTicksPerRev(ticksPerRev);
        type.setMaxRPM(motorTypeMaxVelocityTicksPerSecond / ticksPerRev * 60.0);
        type.setAchieveableMaxRPMFraction(1.0);
        return type;
    }
    @Override public void setMotorType(MotorConfigurationType motorType) { /* no-op */ }
    @Override public int getPortNumber() { return 0; }

    @Override
    public DcMotorController getController() {
        throw new UnsupportedOperationException(
                "FakeExternalEncoder has no DcMotorController in sim — read state via getCurrentPosition()");
    }

    // ---- DcMotorEx ----
    // A position sensor: velocity READS work (finite-differenced counts); closed-loop velocity/PIDF control
    // and current draw are not modelled (no-op / zero), exactly as on an encoder-only port.
    @Override public double getVelocity() {
        // Finite-differenced from the raw (unquantized) injected position, whereas getCurrentPosition()
        // truncates to int — so getVelocity() and Δ(getCurrentPosition())/Δt can differ by up to a tick of
        // quantization. Deliberate simplification: velocity is telemetry here (the slide loop reads position
        // only), and keeping it off the raw value avoids a spurious ±1-tick jitter in the rate.
        return (direction == Direction.REVERSE) ? -velocityTicksPerSecond : velocityTicksPerSecond;
    }
    @Override public double getVelocity(AngleUnit unit) {
        // Mirror the SDK: ticks/s -> rad/s (via CPR), then convert with the UNNORMALIZED unit — this is a
        // rate, not an angle, so it must NOT be wrapped to [-pi,pi].
        double radiansPerSecond = getVelocity() / ticksPerRev * 2.0 * Math.PI;
        return unit.getUnnormalized().fromRadians(radiansPerSecond);
    }
    @Override public void setVelocity(double angularRate) { /* not modelled: this is a sensor */ }
    @Override public void setVelocity(double angularRate, AngleUnit unit) { /* not modelled */ }
    @Override public void setMotorEnable() { motorEnabled = true; }
    @Override public void setMotorDisable() { motorEnabled = false; }
    @Override public boolean isMotorEnabled() { return motorEnabled; }
    @Override public void setPIDCoefficients(RunMode mode, PIDCoefficients pidCoefficients) { /* no closed loop */ }
    @Override public void setPIDFCoefficients(RunMode mode, PIDFCoefficients pidfCoefficients) { /* no-op */ }
    @Override public void setVelocityPIDFCoefficients(double p, double i, double d, double f) { /* no-op */ }
    @Override public void setPositionPIDFCoefficients(double p) { /* no-op */ }
    @Override public PIDCoefficients getPIDCoefficients(RunMode mode) { return new PIDCoefficients(0, 0, 0); }
    @Override public PIDFCoefficients getPIDFCoefficients(RunMode mode) { return new PIDFCoefficients(0, 0, 0, 0); }
    @Override public void setTargetPositionTolerance(int tolerance) { /* no-op */ }
    @Override public int getTargetPositionTolerance() { return 0; }
    @Override public double getCurrent(CurrentUnit unit) { return 0.0; }
    @Override public double getCurrentAlert(CurrentUnit unit) { return currentAlert; }
    @Override public void setCurrentAlert(double current, CurrentUnit unit) { this.currentAlert = current; }
    @Override public boolean isOverCurrent() { return false; }
}
