package org.horizon36596.simloop.fakehardware;

import com.qualcomm.robotcore.hardware.DigitalChannel;
import com.qualcomm.robotcore.hardware.DigitalChannelController;

/**
 * Fake {@link DigitalChannel}: the one digital IO pin on a REV hub that a beam-break sensor, a limit
 * switch or a signal LED is wired to. Unchanged robot code resolves it with
 * {@code hardwareMap.get(DigitalChannel.class, name)} and reads {@code getState()} exactly as it does on
 * the real robot (domain R1).
 *
 * <h2>Two bits, not one — and that is the SDK's own design, not a simplification</h2>
 * {@code DigitalChannelController} documents {@code getDigitalChannelState} as: <i>"If it's in OUTPUT
 * mode, this will return the output bit. If the channel is in INPUT mode, this will return the input
 * bit."</i> {@code LynxDigitalChannelController} implements precisely that — an OUTPUT pin reports the
 * last value the robot wrote, an INPUT pin reports what the wire is actually carrying. So this fake
 * holds both bits:
 *
 * <table border="1">
 *   <caption>Which bit each method touches</caption>
 *   <tr><th>Method</th><th>Touches</th><th>Who calls it</th></tr>
 *   <tr><td>{@link #setState(boolean)}</td><td>the output bit</td>
 *       <td>robot code driving an LED or a signal line</td></tr>
 *   <tr><td>{@link #setSimulatedInputState(boolean)}</td><td>the input bit</td>
 *       <td><b>the sim only</b> — this method does not exist on real hardware</td></tr>
 *   <tr><td>{@link #getState()}</td><td>output bit in {@code OUTPUT} mode, input bit in {@code INPUT}
 *       mode</td><td>robot code reading the sensor</td></tr>
 * </table>
 *
 * <p><b>This is what makes the sensor honest</b> (B13 Contract clause 2). A subsystem under test is
 * wired to read a beam break, which is an {@code INPUT} channel, so no amount of {@code setState} from
 * inside that subsystem can change what it reads back. Only the sim, which owns the physical world, can
 * say that a ball is sitting in the beam. A test that could set its own sensor reading through the robot
 * API would be testing nothing.
 *
 * <p><b>A beam break here is a boolean the sim sets, never a ray cast.</b> Domain R6 rules out contact
 * physics, so nothing in this repo works out geometrically whether a ball is between the emitter and the
 * receiver. The sim decides that a ball is present and sets the bit; how it decides lives in
 * {@code org.horizon36596.simloop.field}, over trigger volumes and possession, not over collisions.
 *
 * <p><b>Polarity is the season's business, not this class's.</b> This fake reports a raw line state. A
 * real break-beam receiver is open-collector with a pull-up, so <b>beam intact = HIGH, beam broken =
 * LOW</b>, which makes "ball present" {@code !getState()} ({@code docs/specs/intake.md} §3). Baking that
 * inversion in here would be a season fact in the core (domain R7) and would silently break the next
 * sensor that is wired the other way round.
 *
 * <h2>Fidelity notes, each read out of the SDK source rather than remembered</h2>
 * <ul>
 *   <li><b>A fresh channel is in {@code INPUT} mode.</b> {@code LynxDigitalChannelController
 *       .initializeHardware()} sets every pin on the module to {@code Mode.INPUT} at startup.</li>
 *   <li><b>Switching {@code INPUT} to {@code OUTPUT} clears the output bit to {@code false}.</b> That is
 *       the hub firmware's behaviour, and {@code LynxDigitalChannelController.setDigitalChannelMode}
 *       records it explicitly. Switching {@code OUTPUT} to {@code OUTPUT} does not clear it.</li>
 *   <li><b>{@code getVersion()} is 1</b>, matching {@code DigitalChannelImpl}, not the {@code 0} this
 *       repo's {@link AbstractFakeDevice} defaults to.</li>
 *   <li><b>A {@code setState} on a pin in {@code INPUT} mode does nothing at all.</b>
 *       {@code LynxDigitalChannelController.setDigitalChannelState} only sends the command, and only
 *       records the bit, when the pin is in {@code OUTPUT} mode — "setting the value of a DIO pin
 *       configured for input will result in a NACK, so we don't bother", in its own words. The SDK's
 *       {@code DigitalChannel.setState} javadoc calls the behaviour "undefined" in {@code INPUT} mode;
 *       the hub's actual answer is "ignored", so that is what this fake does.</li>
 *   <li><b>The deprecated {@code setMode(DigitalChannelController.Mode)} is implemented</b>, not stubbed,
 *       even though nothing in this repo calls it. On real hardware {@code DigitalChannelImpl} hands the
 *       legacy enum to its controller's own deprecated overload, and it is the controller
 *       ({@code LynxDigitalChannelController}) that calls {@code Mode.migrate()}; with no controller in
 *       sim this fake migrates it here, which lands on the identical behaviour. A fake matches the real
 *       device method for method, including the methods this robot never calls.</li>
 * </ul>
 *
 * <p>Deterministic (domain R5): there is nothing to integrate, so {@link #update(double)} does nothing
 * and the state changes only when the sim or the robot sets it.
 */
public class FakeDigitalChannel extends AbstractFakeDevice implements DigitalChannel {

    /**
     * Where the pin starts. A REV hub powers every digital pin up as an input — see the fidelity note in
     * the class javadoc.
     */
    private Mode mode = Mode.INPUT;

    /** What the wire is carrying, set by the sim. Read back by {@link #getState()} in {@code INPUT} mode. */
    private boolean inputBit;

    /** What the robot last wrote. Read back by {@link #getState()} in {@code OUTPUT} mode. */
    private boolean outputBit;

    /**
     * A channel that starts in {@code INPUT} mode reading {@code false}. For a break-beam receiver
     * {@code false} is LOW, which the intake's wiring reads as "beam broken"; seed the resting state the
     * scenario wants with {@link #setSimulatedInputState(boolean)} rather than assuming this default
     * means anything physical.
     */
    public FakeDigitalChannel() {
    }

    /**
     * Drive the wire, as the physical world would. <b>Sim-only — no such method exists on a real
     * {@code DigitalChannel}</b>, which is exactly the point: the scenario decides what the sensor sees,
     * and the subsystem under test has no way to fabricate it (B13 Contract clause 2).
     *
     * @param state the raw line level: {@code true} is HIGH. For a break-beam receiver, HIGH means the
     *              beam is intact and LOW means something is blocking it.
     */
    public void setSimulatedInputState(boolean state) {
        this.inputBit = state;
    }

    /** {@return the raw line level the sim is currently driving, whatever mode the channel happens to be in} */
    public boolean getSimulatedInputState() {
        return inputBit;
    }

    @Override
    public void update(double deltaTime) {
        // A digital pin has no dynamics: its level is whatever the sim or the robot last set.
    }

    // ---- DigitalChannel ----

    @Override
    public Mode getMode() {
        return mode;
    }

    @Override
    public void setMode(Mode mode) {
        // A null mode is a caller bug, and it is one the real device also refuses -- DigitalChannelImpl
        // hands the mode to its controller, which builds a hub command out of it and fails. Storing it
        // would be worse than failing: every mode comparison below is `== Mode.OUTPUT`, so a null channel
        // would quietly behave as an INPUT forever.
        if (mode == null) {
            throw new IllegalArgumentException("digital channel mode must be INPUT or OUTPUT, got null");
        }
        // Hub-firmware behaviour: turning an input pin into an output pin starts it driving LOW, so the
        // pin cannot briefly assert a stale value the robot never wrote.
        if (this.mode == Mode.INPUT && mode == Mode.OUTPUT) {
            this.outputBit = false;
        }
        this.mode = mode;
    }

    /**
     * The pre-2016 spelling of {@link #setMode(Mode)}, kept because the real {@code DigitalChannelImpl}
     * still has it. It migrates the legacy enum through the SDK's own {@code migrate()} and then behaves
     * identically — see the fidelity note in the class javadoc for where that migration happens on real
     * hardware.
     */
    @Override
    @Deprecated
    public void setMode(DigitalChannelController.Mode mode) {
        setMode(mode.migrate());
    }

    /**
     * The channel's current state: the bit the robot last wrote while in {@code OUTPUT} mode, or the line
     * the sim is driving while in {@code INPUT} mode. See the table in the class javadoc.
     */
    @Override
    public boolean getState() {
        return mode == Mode.OUTPUT ? outputBit : inputBit;
    }

    /**
     * Write the output bit, as robot code driving an LED or a signal line would.
     *
     * <p><b>In {@code INPUT} mode this is dropped on the floor</b>, exactly as the hub drops it: the
     * controller does not even send the command, because a DIO pin configured for input NACKs it. So a
     * subsystem wired to a beam break cannot fabricate its own sensor reading, no matter what it writes
     * (B13 Contract clause 2) — only {@link #setSimulatedInputState(boolean)} can, and that method does
     * not exist on real hardware.
     */
    @Override
    public void setState(boolean state) {
        if (mode == Mode.OUTPUT) {
            this.outputBit = state;
        }
    }

    // ---- HardwareDevice: matched to DigitalChannelImpl rather than to AbstractFakeDevice's defaults ----

    /** {@code 1}, as {@code DigitalChannelImpl} reports. */
    @Override
    public int getVersion() {
        return 1;
    }
}
