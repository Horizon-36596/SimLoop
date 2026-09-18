package org.horizon36596.simloop.fakehardware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qualcomm.robotcore.hardware.DigitalChannel;
import com.qualcomm.robotcore.hardware.DigitalChannelController;

import org.junit.jupiter.api.Test;

/**
 * INVARIANT-tier (domain R9) — B13 Contract clause 2: a beam break is readable through a fake that
 * implements the SDK's {@link DigitalChannel} as the real device does, and <b>its state is set by the
 * sim, not by the subsystem under test</b>.
 *
 * <p>The second half is the one that matters. A sensor a subsystem can write to is a sensor that proves
 * nothing: a test could set its own "ball present" bit and watch its own code agree with it. Every claim
 * below about the hardware was read out of the SDK source — {@code LynxDigitalChannelController} and
 * {@code DigitalChannelImpl} — rather than remembered, and the fidelity notes on the class say which line
 * each came from.
 */
class FakeDigitalChannelTest {

    // ---- The clause: only the sim can say what the wire is carrying ----

    @Test
    void theSubsystemUnderTestCannotFabricateItsOwnSensorReading() {
        // A beam break is an INPUT channel. On real hardware a setState on an input pin is NACKed and the
        // controller does not even send it, so robot code has no path to its own sensor value.
        FakeDigitalChannel beamBreak = new FakeDigitalChannel();
        beamBreak.setSimulatedInputState(true);

        beamBreak.setState(false);   // robot code trying to write the bit it reads

        assertTrue(beamBreak.getState(),
                "a subsystem writing to an INPUT channel must not change what it reads back — only the "
                        + "sim owns the physical world");
    }

    @Test
    void theSimDrivesTheWireAndTheChannelReportsIt() {
        FakeDigitalChannel beamBreak = new FakeDigitalChannel();

        beamBreak.setSimulatedInputState(false);
        assertFalse(beamBreak.getState(), "LOW on the wire reads back LOW");

        beamBreak.setSimulatedInputState(true);
        assertTrue(beamBreak.getState(), "HIGH on the wire reads back HIGH");
    }

    @Test
    void aBallInTheBeamIsASimSetBooleanAndNeverAGeometryQuery() {
        // Domain R6 forbids contact physics, so "ball present" is a bit the scenario sets. This test pins
        // the SHAPE of that: the channel exposes no position, no geometry and no notion of a ball at all —
        // it is a wire. The season's polarity (present == !getState(), docs/specs/intake.md §3) is applied
        // by season code, not here, which is why this core class stays season-agnostic (domain R7).
        FakeDigitalChannel beamBreak = new FakeDigitalChannel();

        beamBreak.setSimulatedInputState(false);        // something is blocking the beam
        boolean ballPresent = !beamBreak.getState();    // the season's reading of that

        assertTrue(ballPresent, "beam broken (LOW) is how a break-beam receiver reports a ball");
        assertFalse(beamBreak.getSimulatedInputState(), "and the raw line is readable as itself");
    }

    // ---- Device fidelity: the two-bit model the SDK actually specifies ----

    @Test
    void aFreshChannelIsAnInput() {
        // LynxDigitalChannelController.initializeHardware() sets every pin on the module to Mode.INPUT.
        assertEquals(DigitalChannel.Mode.INPUT, new FakeDigitalChannel().getMode(),
                "a REV hub powers every digital pin up as an input");
    }

    @Test
    void anOutputChannelReportsTheBitTheRobotWroteNotTheWire() {
        // DigitalChannelController.getDigitalChannelState: "If it's in OUTPUT mode, this will return the
        // output bit. If the channel is in INPUT mode, this will return the input bit."
        FakeDigitalChannel led = new FakeDigitalChannel();
        led.setSimulatedInputState(true);       // the wire says HIGH...
        led.setMode(DigitalChannel.Mode.OUTPUT);
        led.setState(false);

        assertFalse(led.getState(), "...but an OUTPUT pin reports what the robot last wrote");

        led.setState(true);
        assertTrue(led.getState(), "and follows it when the robot writes again");
    }

    @Test
    void switchingAnInputToAnOutputClearsTheOutputBit() {
        // LynxDigitalChannelController.setDigitalChannelMode: "If direction is changed from input to
        // output, the output value is initially set to 0 by the FW".
        FakeDigitalChannel pin = new FakeDigitalChannel();
        pin.setMode(DigitalChannel.Mode.OUTPUT);
        pin.setState(true);
        pin.setMode(DigitalChannel.Mode.INPUT);
        pin.setMode(DigitalChannel.Mode.OUTPUT);

        assertFalse(pin.getState(),
                "coming back out of INPUT mode, the pin starts driving LOW rather than asserting a stale "
                        + "value the robot never re-wrote");
    }

    @Test
    void switchingAnOutputToAnOutputDoesNotClearTheOutputBit() {
        // The firmware clears only on the INPUT->OUTPUT edge, so a redundant setMode must be harmless.
        FakeDigitalChannel pin = new FakeDigitalChannel();
        pin.setMode(DigitalChannel.Mode.OUTPUT);
        pin.setState(true);
        pin.setMode(DigitalChannel.Mode.OUTPUT);

        assertTrue(pin.getState(), "a redundant setMode(OUTPUT) must not drop the bit being driven");
    }

    @Test
    void aBitWrittenWhileAnInputNeverBecomesVisible() {
        // Belt and braces on the clause above: even the one path that could smuggle a written bit into a
        // readable place — write it as an input, then become an output — is closed, because that edge
        // clears the bit.
        FakeDigitalChannel beamBreak = new FakeDigitalChannel();
        beamBreak.setState(true);
        beamBreak.setMode(DigitalChannel.Mode.OUTPUT);

        assertFalse(beamBreak.getState(),
                "a bit written while the pin was an input is gone, as it is on hardware");
    }

    @Test
    void theDeprecatedSetModeStillWorks() {
        // DigitalChannelImpl still carries it, so the fake carries it: a fake matches the real device
        // method for method, including the methods this robot never calls.
        FakeDigitalChannel pin = new FakeDigitalChannel();

        pin.setMode(DigitalChannelController.Mode.OUTPUT);
        assertEquals(DigitalChannel.Mode.OUTPUT, pin.getMode(), "the legacy OUTPUT enum migrates");

        pin.setMode(DigitalChannelController.Mode.INPUT);
        assertEquals(DigitalChannel.Mode.INPUT, pin.getMode(), "and so does the legacy INPUT enum");
    }

    @Test
    void aNullModeIsRejectedRatherThanStored() {
        // Every mode comparison in the class is `== Mode.OUTPUT`, so a stored null would quietly behave as
        // an INPUT forever -- the silent-wrong this repo fails loud on instead.
        assertThrows(IllegalArgumentException.class,
                () -> new FakeDigitalChannel().setMode((DigitalChannel.Mode) null));
    }

    @Test
    void reportsTheSameVersionTheRealDigitalChannelReports() {
        assertEquals(1, new FakeDigitalChannel().getVersion(),
                "getVersion() must match DigitalChannelImpl's 1, not AbstractFakeDevice's 0 default");
    }

    @Test
    void hasNoDynamicsToTick() {
        FakeDigitalChannel beamBreak = new FakeDigitalChannel();
        beamBreak.setSimulatedInputState(true);
        beamBreak.update(0.02);

        assertTrue(beamBreak.getState(), "a digital pin's level is whatever was last set; ticking is a no-op");
    }

    @Test
    void unchangedRobotCodeResolvesItByNameThroughTheHardwareMap() {
        // Domain R1: the subsystem asks for DigitalChannel.class by its config name, exactly as it does on
        // the real robot, and gets this fake back.
        FakeDigitalChannel beamBreak = new FakeDigitalChannel();
        FakeHardwareMap map = new FakeHardwareMap();
        map.register("intakeBeam1a", beamBreak);

        DigitalChannel resolved = map.get(DigitalChannel.class, "intakeBeam1a");
        assertEquals(beamBreak, resolved, "the subsystem gets the same device the sim is driving");

        beamBreak.setSimulatedInputState(true);
        assertTrue(resolved.getState(), "and reads the sim's bit through the SDK interface");
    }
}
