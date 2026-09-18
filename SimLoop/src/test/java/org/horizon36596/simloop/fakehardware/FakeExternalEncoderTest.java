package org.horizon36596.simloop.fakehardware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

import org.junit.jupiter.api.Test;

/**
 * Batch 2.2: {@link FakeExternalEncoder} — the plant &rarr; sensor seam for a 1-DOF mechanism. Pins the
 * behaviours the slide subsystem depends on: position is <b>injected</b> (not integrated), velocity is
 * finite-differenced from injected counts (never a wall-clock, domain R5), the SDK direction/CPR contracts
 * hold, and non-finite input fails loud (fakehardware-plant §0).
 */
class FakeExternalEncoderTest {

    private static final double DT = 0.02;
    private static final double CPR = 103.6;

    private static FakeExternalEncoder encoder() {
        // maxVel ticks/s = CPR * 1620rpm / 60; only feeds getMotorType() coherence.
        return new FakeExternalEncoder(CPR * 1620.0 / 60.0, CPR);
    }

    @Test
    void injectedPositionReadsBackImmediately() {
        FakeExternalEncoder enc = encoder();
        enc.setEncoderPosition(500.7);
        // Truncated to int like a real encoder count (SDK getCurrentPosition returns int).
        assertEquals(500, enc.getCurrentPosition());
    }

    @Test
    void commandedPowerDoesNotMovePositionItIsASensor() {
        FakeExternalEncoder enc = encoder();
        enc.setPower(1.0);
        for (int i = 0; i < 100; i++) enc.update(DT);
        // No injection -> no motion. A sensor's count comes only from the shaft (injection), never power.
        assertEquals(0, enc.getCurrentPosition());
        assertEquals(1.0, enc.getPower());   // stored for API fidelity
    }

    @Test
    void velocityIsFiniteDifferenceOfInjectedPosition() {
        FakeExternalEncoder enc = encoder();
        enc.setEncoderPosition(0.0);
        enc.update(DT);                 // establish baseline
        enc.setEncoderPosition(200.0);  // moved 200 ticks over the next tick
        enc.update(DT);
        assertEquals(200.0 / DT, enc.getVelocity(), 1e-9);
    }

    @Test
    void reverseDirectionNegatesCountAndVelocity() {
        FakeExternalEncoder enc = encoder();
        enc.setDirection(DcMotorSimple.Direction.REVERSE);
        enc.setEncoderPosition(0.0);
        enc.update(DT);
        enc.setEncoderPosition(300.0);
        enc.update(DT);
        assertEquals(-300, enc.getCurrentPosition());
        assertEquals(-300.0 / DT, enc.getVelocity(), 1e-9);
    }

    @Test
    void nonFiniteInjectedPositionFailsLoud() {
        FakeExternalEncoder enc = encoder();
        assertThrows(IllegalArgumentException.class, () -> enc.setEncoderPosition(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> enc.setEncoderPosition(Double.POSITIVE_INFINITY));
    }

    @Test
    void nonPositiveOrNonFiniteDeltaTimeLeavesVelocityUntouched() {
        FakeExternalEncoder enc = encoder();
        // First establish a known NON-ZERO velocity with two valid ticks, so the assertion below can't pass
        // trivially off the rest value.
        enc.setEncoderPosition(0.0);
        enc.update(DT);
        enc.setEncoderPosition(150.0);
        enc.update(DT);
        double established = enc.getVelocity();
        assertEquals(150.0 / DT, established, 1e-9);   // sanity: baseline really is non-zero

        // Now a bad dt is not a real tick (FakeTimer is monotonic): even with a fresh injected position it
        // must NOT recompute velocity — the previously established value is left untouched (never NaN/Inf).
        enc.setEncoderPosition(999.0);
        enc.update(0.0);
        enc.update(-1.0);
        enc.update(Double.NaN);
        assertEquals(established, enc.getVelocity(), 0.0);
    }

    @Test
    void ctorRejectsNonPositiveTicksPerRev() {
        assertThrows(IllegalArgumentException.class, () -> new FakeExternalEncoder(1000.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new FakeExternalEncoder(1000.0, -5.0));
    }

    @Test
    void motorTypeReportsRealCprAndAchievableMax() {
        FakeExternalEncoder enc = encoder();
        MotorConfigurationType type = enc.getMotorType();
        assertEquals(CPR, type.getTicksPerRev());
        // getAchieveableMaxTicksPerSecond == the maxVel passed in (ticksPerRev * maxRPM * fraction / 60).
        assertEquals(CPR * 1620.0 / 60.0, type.getAchieveableMaxTicksPerSecond(), 1e-6);
    }
}
