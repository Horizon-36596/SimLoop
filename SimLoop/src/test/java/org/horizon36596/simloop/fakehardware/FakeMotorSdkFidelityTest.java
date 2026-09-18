package org.horizon36596.simloop.fakehardware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qualcomm.robotcore.hardware.DcMotor;

import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link FakeMotor} behaviours that an adversarial review on 2026-09-17 found were accepted and
 * then ignored — {@code setMode}, {@code setVelocity} and {@code setMotorDisable} each took a command,
 * reported success, and changed nothing about what the shaft did.
 *
 * <p>Every expectation here was read off the FTC SDK 11.2.1 sources ({@code DcMotorImpl},
 * {@code DcMotorImplEx}, {@code LynxDcMotorController}) rather than off what seemed reasonable. That is the
 * whole point of the fidelity rule: the fake is only worth having if a divergence from the real device is a
 * test failure.
 */
class FakeMotorSdkFidelityTest {

    private static final double MAX_VELOCITY_TICKS_PER_SECOND = 1000.0;
    private static final double MAX_ACCEL = 10.0;
    private static final double TICK = 0.02;

    private static FakeMotor spunUpMotor() {
        FakeMotor motor = new FakeMotor(MAX_VELOCITY_TICKS_PER_SECOND, MAX_ACCEL);
        motor.setPower(1.0);
        for (int i = 0; i < 50; i++) {
            motor.update(TICK);
        }
        return motor;
    }

    @Test
    void aDisabledMotorStopsDrivingEvenThoughItsCommandedPowerIsUnchanged() {
        FakeMotor motor = spunUpMotor();
        double positionWhenDisabled = motor.getPhysicalPositionTicks();

        motor.setMotorDisable();
        for (int i = 0; i < 200; i++) {
            motor.update(TICK);
        }

        assertFalse(motor.isMotorEnabled(), "the channel reports itself disabled");
        assertEquals(1.0, motor.getPower(), 1e-9,
                "the commanded power survives, as it does on a real controller — only the channel is off");
        assertEquals(0.0, motor.getSpeed(), 1e-6, "the shaft coasts to a stop with no drive behind it");
        // It coasts rather than stopping dead, so it travels a little further before it settles. What must
        // not happen is what used to: the encoder climbing forever while the motor reported itself disabled.
        double travelledWhileDisabled = motor.getPhysicalPositionTicks() - positionWhenDisabled;
        assertTrue(travelledWhileDisabled < MAX_VELOCITY_TICKS_PER_SECOND * TICK * 20,
                "a disabled motor coasts to a stop, it does not keep driving; travelled "
                        + travelledWhileDisabled + " ticks");
    }

    @Test
    void aFreshPowerCommandReEnablesTheChannelTheWayTheRealControllerDoes() {
        FakeMotor motor = spunUpMotor();
        motor.setMotorDisable();
        assertFalse(motor.isMotorEnabled());

        motor.setPower(0.5);

        assertTrue(motor.isMotorEnabled(),
                "LynxDcMotorController.internalSetMotorPower enables the channel on every power command");
    }

    @Test
    void stopAndResetEncoderStopsTheShaftAndZeroesTheCount() {
        FakeMotor motor = spunUpMotor();
        assertTrue(motor.getPhysicalPositionTicks() > 0.0, "precondition: the motor is actually turning");

        motor.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);

        assertEquals(DcMotor.RunMode.STOP_AND_RESET_ENCODER, motor.getMode(),
                "the mode is reported back, not swallowed");
        assertEquals(0, motor.getCurrentPosition(), "the encoder is reset");
        assertEquals(0.0, motor.getSpeed(), 1e-9, "the shaft is stopped");

        // And it STAYS stopped: the real controller refuses to send a power command while in this mode, so
        // nothing can start the motor again until the mode changes.
        for (int i = 0; i < 100; i++) {
            motor.update(TICK);
        }
        assertEquals(0, motor.getCurrentPosition(), "the count stays at zero while the mode holds");
        assertEquals(1.0, motor.getPower(), 1e-9,
                "the API-visible power is preserved across the reset, matching LynxDcMotorController");
    }

    @Test
    void aMotorThatIsNotDrivingDoesNotDrawStallCurrent() {
        // Current draw is how robot code detects a jam, so the shaft and the ammeter have to tell the same
        // story. Both ways of stopping a motor leave getPower() reporting the commanded value, and a naive
        // current model reads that value and reports full stall current for a motor that is being held
        // still — which a stall detector cannot tell apart from a real jam.
        FakeMotor stoppedByMode = spunUpMotor();
        stoppedByMode.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        stoppedByMode.update(TICK);
        assertEquals(0.0, stoppedByMode.getCurrent(CurrentUnit.AMPS), 1e-9,
                "a motor the mode is holding stopped draws nothing");

        FakeMotor stoppedByDisable = spunUpMotor();
        stoppedByDisable.setMotorDisable();
        for (int i = 0; i < 200; i++) {
            stoppedByDisable.update(TICK);
        }
        assertEquals(0.0, stoppedByDisable.getCurrent(CurrentUnit.AMPS), 1e-6,
                "and neither does one whose channel is disabled");

        FakeMotor driving = spunUpMotor();
        assertTrue(driving.getCurrent(CurrentUnit.AMPS) > 0.0,
                "while a motor that IS driving still draws current — the check above is not vacuous");
    }

    @Test
    void resettingTheEncoderAlsoDropsAStalePlantShaftSpeed() {
        // A mechanism plant can take authority over the shaft speed. That measurement is stale the instant
        // the encoder is reset, because this mode holds the shaft still; left in place it made getSpeed()
        // report a turning shaft on a stopped motor.
        FakeMotor motor = spunUpMotor();
        motor.setMeasuredShaftSpeedFraction(0.5);

        motor.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        motor.update(TICK);

        assertEquals(0.0, motor.getSpeed(), 1e-9, "the shaft reads as stopped, not as still turning at 0.5");
    }

    @Test
    void theModeIsWhateverWasSetRatherThanAlwaysRunWithoutEncoder() {
        FakeMotor motor = new FakeMotor(MAX_VELOCITY_TICKS_PER_SECOND, MAX_ACCEL);
        assertEquals(DcMotor.RunMode.RUN_WITHOUT_ENCODER, motor.getMode(), "the mode a fresh motor is in");

        motor.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        assertEquals(DcMotor.RunMode.RUN_USING_ENCODER, motor.getMode());

        motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        assertEquals(DcMotor.RunMode.RUN_WITHOUT_ENCODER, motor.getMode());
    }

    @Test
    void runToPositionIsRefusedRatherThanAcceptedAndIgnored() {
        FakeMotor motor = new FakeMotor(MAX_VELOCITY_TICKS_PER_SECOND, MAX_ACCEL);

        UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class,
                () -> motor.setMode(DcMotor.RunMode.RUN_TO_POSITION));

        assertTrue(refused.getMessage().contains("RUN_TO_POSITION"),
                "the message names the mode that was refused");
        assertFalse(motor.isBusy(), "isBusy stays false, which is what the real controller reports outside RTP");
    }

    @Test
    void setVelocityActuallyTurnsTheShaft() {
        FakeMotor motor = new FakeMotor(MAX_VELOCITY_TICKS_PER_SECOND, MAX_ACCEL);

        motor.setVelocity(500.0);
        for (int i = 0; i < 100; i++) {
            motor.update(TICK);
        }

        assertEquals(DcMotor.RunMode.RUN_USING_ENCODER, motor.getMode(),
                "LynxDcMotorController.setMotorVelocity switches an unsuitable mode to RUN_USING_ENCODER");
        assertTrue(motor.isMotorEnabled(), "and enables the channel");
        assertEquals(500.0, motor.getVelocity(), 1.0,
                "the shaft settles at the commanded ticks/second rather than standing still");
    }

    @Test
    void setVelocityRespectsTheConfiguredDirection() {
        FakeMotor reversed = new FakeMotor(MAX_VELOCITY_TICKS_PER_SECOND, MAX_ACCEL);
        reversed.setDirection(DcMotor.Direction.REVERSE);

        reversed.setVelocity(500.0);
        for (int i = 0; i < 100; i++) {
            reversed.update(TICK);
        }

        assertEquals(500.0, reversed.getVelocity(), 1.0,
                "the SDK-reported velocity is in the motor's logical frame, so it reads back positive");
        assertTrue(reversed.getPhysicalVelocityTicksPerSecond() < 0.0,
                "while the physical shaft really does turn the other way");
    }

    @Test
    void setVelocitySaturatesAtTheMotorsFreeSpeedRatherThanExceedingIt() {
        FakeMotor motor = new FakeMotor(MAX_VELOCITY_TICKS_PER_SECOND, MAX_ACCEL);

        motor.setVelocity(MAX_VELOCITY_TICKS_PER_SECOND * 10.0);
        for (int i = 0; i < 200; i++) {
            motor.update(TICK);
        }

        assertEquals(MAX_VELOCITY_TICKS_PER_SECOND, motor.getVelocity(), 1.0,
                "a motor cannot be commanded past its own free speed, in sim or on the field");
    }

    @Test
    void aPowerCommandWhileTheEncoderIsResettingDoesNotBringADisabledChannelBack() {
        // LynxDcMotorController.internalSetMotorPower builds command = null in STOP_AND_RESET_ENCODER
        // ("setting motor power in this mode doesn't do anything") and only calls internalSetMotorEnable
        // when it built a command. So this one mode is the exception to "a power command re-enables".
        FakeMotor motor = spunUpMotor();
        motor.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        motor.setMotorDisable();

        motor.setPower(1.0);

        assertFalse(motor.isMotorEnabled(),
                "a power command is discarded outright while the encoder is resetting, so it cannot "
                        + "re-enable the channel either");
    }

    @Test
    void anyRealModeChangeBringsADisabledChannelBack() {
        // Every route through LynxDcMotorController.setMotorMode ends in a power command, and a power
        // command that is actually sent calls internalSetMotorEnable(true). Entering STOP_AND_RESET_ENCODER
        // counts, because its internalSetMotorPower(motor, 0) runs BEFORE the new mode is recorded and so
        // is built in the outgoing mode.
        FakeMotor intoReset = spunUpMotor();
        intoReset.setMotorDisable();
        intoReset.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        assertTrue(intoReset.isMotorEnabled(), "entering the reset mode re-enables the channel");

        FakeMotor outOfReset = spunUpMotor();
        outOfReset.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        outOfReset.setMotorDisable();
        outOfReset.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        assertTrue(outOfReset.isMotorEnabled(), "and so does leaving it");

        // Setting the mode it is ALREADY in is a no-op on the real controller, which guards its whole body
        // on "if the mode is not already this one" -- so it must not re-enable either.
        FakeMotor sameMode = spunUpMotor();
        sameMode.setMotorDisable();
        sameMode.setMode(sameMode.getMode());
        assertFalse(sameMode.isMotorEnabled(),
                "re-setting the mode it is already in changes nothing, including the enable");
    }

    @Test
    void aRunToPositionTargetIsRecordedInEveryModeEvenThoughNothingDrivesToIt() {
        // DcMotorImpl accepts and reports a target in any mode, so subsystem code that sets one during
        // configuration reads back the same value here as on the robot. Nothing acts on it, because
        // setMode(RUN_TO_POSITION) is refused -- this pins "recorded, never acted on", not "drives there".
        FakeMotor motor = new FakeMotor(MAX_VELOCITY_TICKS_PER_SECOND, MAX_ACCEL);

        motor.setTargetPosition(750);
        motor.setTargetPositionTolerance(8);

        assertEquals(750, motor.getTargetPosition(), "the target round-trips outside RUN_TO_POSITION");
        assertEquals(8, motor.getTargetPositionTolerance(), "and so does the tolerance");

        motor.setPower(1.0);
        for (int i = 0; i < 100; i++) {
            motor.update(TICK);
        }
        assertTrue(motor.getCurrentPosition() > 750,
                "and nothing stops at it -- the target is stored state, not a controller");
        assertFalse(motor.isBusy(), "isBusy stays false, since no position loop was ever entered");
    }
}
