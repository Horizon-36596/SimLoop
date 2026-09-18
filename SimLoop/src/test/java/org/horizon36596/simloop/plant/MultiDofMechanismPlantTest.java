package org.horizon36596.simloop.plant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.horizon36596.simloop.config.ContinuousRotationSimConfig;
import org.horizon36596.simloop.config.DrivetrainSimConfig;
import org.horizon36596.simloop.config.MechanismSimConfig;
import org.horizon36596.simloop.config.PositionalServoSimConfig;
import org.horizon36596.simloop.config.SimRobotConfig;
import org.horizon36596.simloop.fakehardware.FakeCRServo;
import org.horizon36596.simloop.fakehardware.FakeMotor;
import org.horizon36596.simloop.fakehardware.FakeServo;
import org.horizon36596.simloop.sim.FakeTimer;
import org.horizon36596.simloop.sim.RlogDecodedCompare;
import org.horizon36596.simloop.sim.ScenarioRunner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * INVARIANT-tier (domain R9) — B13 Contract clause 1: a subsystem with several real degrees of freedom is
 * simulated by composing <b>one plant per degree of freedom</b>, each producing exactly what a lone
 * {@link Mechanism1DofPlant} or {@link PositionalServoPlant} would produce for that degree of freedom
 * alone, with no plant reading another plant's state, and the composite logged so a reader can tell which
 * degree of freedom a number belongs to.
 *
 * <p><b>How the headline test avoids being circular.</b> It does not ask the composite whether it agrees
 * with itself. It builds the mechanism twice — once as four plants inside a
 * {@link MultiDofMechanismPlant}, once as the same four plants standing alone with their own actuators —
 * drives both with one command schedule, and requires the two to agree <b>bit for bit at every tick</b>.
 * A composite that did any physics of its own, or that let one degree of freedom see another, could not
 * pass that no matter how it was written.
 *
 * <p>The shape under test is V0's {@code Deposit} (BACKLOG B13): a motor-driven slide in inches, a
 * servo-driven pivot in radians, a continuous-rotation ejector in output revolutions, and a servo-driven
 * latch in radians. Four degrees of freedom, three kinds of actuator, four different units — which is the
 * point, because a composite that assumed one unit or one actuator kind would be useless for it.
 */
class MultiDofMechanismPlantTest {

    private static final double DT = 0.02;
    private static final int TICKS = 200;

    // ---- The four degrees of freedom of V0's Deposit, as configs ----

    /** Slide: inches of carriage travel, hard-stopped at both ends, gravity-loaded (docs/specs/deposit.md §4). */
    private static final MechanismSimConfig SLIDE = new MechanismSimConfig() {
        @Override public double timeConstant() { return 0.15; }
        @Override public double maxSpeed() { return 40.0; }
        @Override public double minPosition() { return 0.0; }
        @Override public double maxPosition() { return 28.3465; }
        @Override public double gravityHoldPowerFraction() { return 0.12; }
    };

    /** Pivot: radians, aimed by a positional servo. */
    private static final PositionalServoSimConfig PIVOT = new PositionalServoSimConfig() {
        @Override public double timeConstant() { return 0.08; }
        @Override public double maxSpeed() { return 6.0; }
        @Override public double minPosition() { return 0.0; }
        @Override public double maxPosition() { return 1.4; }
        @Override public double positionAtCommandZero() { return 0.0; }
        @Override public double positionAtCommandOne() { return 1.4; }
    };

    /** Latch: radians, a second positional servo with a much shorter sweep. */
    private static final PositionalServoSimConfig LATCH = new PositionalServoSimConfig() {
        @Override public double timeConstant() { return 0.05; }
        @Override public double maxSpeed() { return 10.0; }
        @Override public double minPosition() { return 0.0; }
        @Override public double maxPosition() { return 0.6; }
        @Override public double positionAtCommandZero() { return 0.0; }
        @Override public double positionAtCommandOne() { return 0.6; }
    };

    /** Ejector: output revolutions, no end stop in either direction. */
    private static final ContinuousRotationSimConfig EJECTOR = new ContinuousRotationSimConfig() {
        @Override public double timeConstant() { return 0.06; }
        @Override public double maxSpeed() { return 3.5; }
    };

    // ---- Clause 1, first half: each degree of freedom moves exactly as it would alone ----

    @Test
    void eachDegreeOfFreedomMovesExactlyAsTheSamePlantWouldAlone() {
        Deposit composed = new Deposit();
        Deposit standalone = new Deposit();
        MultiDofMechanismPlant composite = composed.asComposite();
        double furthestSlide = 0.0;
        double furthestEjector = 0.0;

        for (int tick = 0; tick < TICKS; tick++) {
            composed.command(tick);
            standalone.command(tick);

            // The composite ticks all four from one call; the standalone set is ticked one plant at a
            // time. That difference is the whole point of the comparison.
            composite.update(DT);
            standalone.slidePlant.update(DT);
            standalone.pivotPlant.update(DT);
            standalone.ejectorPlant.update(DT);
            standalone.latchPlant.update(DT);

            assertEquals(standalone.slidePlant.getPosition(), composite.getPosition("slide"), 0.0,
                    "slide position diverged at tick " + tick);
            assertEquals(standalone.slidePlant.getVelocity(), composite.getVelocity("slide"), 0.0,
                    "slide velocity diverged at tick " + tick);
            assertEquals(standalone.pivotPlant.getPosition(), composite.getPosition("pivot"), 0.0,
                    "pivot position diverged at tick " + tick);
            assertEquals(standalone.pivotPlant.getVelocity(), composite.getVelocity("pivot"), 0.0,
                    "pivot velocity diverged at tick " + tick);
            assertEquals(standalone.ejectorPlant.getPosition(), composite.getPosition("ejector"), 0.0,
                    "ejector travel diverged at tick " + tick);
            assertEquals(standalone.ejectorPlant.getVelocity(), composite.getVelocity("ejector"), 0.0,
                    "ejector speed diverged at tick " + tick);
            assertEquals(standalone.latchPlant.getPosition(), composite.getPosition("latch"), 0.0,
                    "latch position diverged at tick " + tick);
            assertEquals(standalone.latchPlant.getVelocity(), composite.getVelocity("latch"), 0.0,
                    "latch velocity diverged at tick " + tick);

            furthestSlide = Math.max(furthestSlide, composite.getPosition("slide"));
            furthestEjector = Math.max(furthestEjector, composite.getPosition("ejector"));
        }

        // Guard against the test passing by everything sitting still. It is the FURTHEST point reached,
        // not the final one: the schedule deliberately retracts the slide back onto its lower hardstop and
        // unwinds the ejector, so both finish near where they started.
        assertTrue(furthestSlide > 1.0,
                "the schedule must actually have driven the slide; furthest was " + furthestSlide);
        assertTrue(furthestEjector > 1.0,
                "and must actually have spun the ejector; furthest was " + furthestEjector);
    }

    // ---- Clause 1, second half: no plant reads another plant's state ----

    @Test
    void drivingOneDegreeOfFreedomLeavesTheOthersExactlyWhereTheyWere() {
        Deposit deposit = new Deposit();
        MultiDofMechanismPlant composite = deposit.asComposite();

        // Only the slide is commanded. A composite that coupled its degrees of freedom -- a heavy slide
        // loading the pivot that carries it -- would show it here, and that coupling is rigid-body
        // physics, which domain R6 rules out.
        deposit.slideMotor.setPower(1.0);
        for (int tick = 0; tick < TICKS; tick++) {
            composite.update(DT);
        }

        assertTrue(composite.getPosition("slide") > 1.0, "the slide must have moved, or this proves nothing");
        assertEquals(0.0, composite.getPosition("pivot"), 0.0, "the pivot must not have felt the slide");
        assertEquals(0.0, composite.getPosition("ejector"), 0.0, "nor the ejector");
        assertEquals(0.0, composite.getPosition("latch"), 0.0, "nor the latch");
        assertEquals(0.0, composite.getVelocity("pivot"), 0.0, "and none of them acquired a velocity");
        assertEquals(0.0, composite.getVelocity("ejector"), 0.0, "and none of them acquired a velocity");
        assertEquals(0.0, composite.getVelocity("latch"), 0.0, "and none of them acquired a velocity");
    }

    @Test
    void registrationOrderDoesNotChangeWhatTheDegreesOfFreedomDo() {
        // If any plant read another, the order they are ticked in would show up in the numbers. It must
        // not -- which is also why the order is fixed rather than sorted (domain R5).
        Deposit forwards = new Deposit();
        Deposit backwards = new Deposit();
        MultiDofMechanismPlant inOrder = forwards.asComposite();
        MultiDofMechanismPlant reversed = MultiDofMechanismPlant.builder()
                .addDof("latch", backwards.latchPlant, "Rad")
                .addDof("ejector", backwards.ejectorPlant, "Rev")
                .addDof("pivot", backwards.pivotPlant, "Rad")
                .addDof("slide", backwards.slidePlant, "In")
                .build();

        for (int tick = 0; tick < TICKS; tick++) {
            forwards.command(tick);
            backwards.command(tick);
            inOrder.update(DT);
            reversed.update(DT);
        }

        for (String dof : new String[] {"slide", "pivot", "ejector", "latch"}) {
            assertEquals(inOrder.getPosition(dof), reversed.getPosition(dof), 0.0,
                    "registration order must not change " + dof);
        }
    }

    // ---- Clause 1, third half: a reader can tell which degree of freedom a number belongs to ----

    @Test
    void everyDegreeOfFreedomIsLoggedUnderItsOwnNameAndUnitCarryingItsOwnNumber() throws IOException {
        // This asserts VALUES, not just key names, and the difference matters. A record() that wrote
        // every degree of freedom's key but filled them all from one plant would satisfy a key-name check
        // perfectly while making the log useless -- which is the exact failure the clause ("a reader can
        // tell which DOF a number belongs to") exists to stop. So the run below drives the four degrees
        // of freedom to four DIFFERENT positions, then reads each number back out of the decoded RLOG and
        // requires it to equal that degree of freedom's own plant.
        Path rlog = Paths.get("build", "sim", "multi-dof-keys.rlog");
        Deposit deposit = new Deposit();
        MultiDofMechanismPlant composite = deposit.asComposite();
        FakeTimer timer = new FakeTimer();

        ScenarioRunner.run("MultiDofKeys", new TestRobotConfig(), timer, 40, DT, rlog, dt -> {
            deposit.slideMotor.setPower(1.0);
            deposit.pivotServo.setPosition(1.0);
            deposit.ejectServo.setPower(1.0);
            deposit.latchServo.setPosition(0.5);
            composite.update(dt);
            composite.record("DepositPlant");
        });

        // Four genuinely different numbers, so a key wired to the wrong plant cannot pass by coincidence.
        assertDistinct(composite.getPosition("slide"), composite.getPosition("pivot"),
                composite.getPosition("ejector"), composite.getPosition("latch"));

        List<Map<String, String>> frames = RlogDecodedCompare.decode(rlog);
        Map<String, String> last = frames.get(frames.size() - 1);

        assertLoggedValue(last, "DepositPlant/slide/positionIn", composite.getPosition("slide"));
        assertLoggedValue(last, "DepositPlant/slide/velocityInPerSecond", composite.getVelocity("slide"));
        assertLoggedValue(last, "DepositPlant/pivot/positionRad", composite.getPosition("pivot"));
        assertLoggedValue(last, "DepositPlant/pivot/velocityRadPerSecond", composite.getVelocity("pivot"));
        assertLoggedValue(last, "DepositPlant/ejector/positionRev", composite.getPosition("ejector"));
        assertLoggedValue(last, "DepositPlant/ejector/velocityRevPerSecond",
                composite.getVelocity("ejector"));
        assertLoggedValue(last, "DepositPlant/latch/positionRad", composite.getPosition("latch"));
        assertLoggedValue(last, "DepositPlant/latch/velocityRadPerSecond", composite.getVelocity("latch"));
    }

    @Test
    void theLogCarriesNothingBeyondTheTwoKeysPerDegreeOfFreedom() throws IOException {
        // Rule 4 of the logging contract bounds the per-loop field count, and rule 3 scopes
        // target/measured/error/atTarget to position-controlled mechanisms -- which a plant is not. So the
        // composite must publish exactly eight keys for four degrees of freedom and no more.
        Path rlog = Paths.get("build", "sim", "multi-dof-key-count.rlog");
        Deposit deposit = new Deposit();
        MultiDofMechanismPlant composite = deposit.asComposite();
        FakeTimer timer = new FakeTimer();

        ScenarioRunner.run("MultiDofKeyCount", new TestRobotConfig(), timer, 3, DT, rlog, dt -> {
            deposit.command(1);
            composite.update(dt);
            composite.record("DepositPlant");
        });

        Set<String> ours = new TreeSet<>();
        for (String key : RlogDecodedCompare.decodedKeys(rlog)) {
            if (key.contains("DepositPlant/")) {
                ours.add(key);
            }
        }
        assertEquals(8, ours.size(),
                "four degrees of freedom publish exactly two keys each; got " + ours);
    }

    @Test
    void recordRejectsABlankKeyPrefix() {
        MultiDofMechanismPlant composite = new Deposit().asComposite();
        assertThrows(IllegalArgumentException.class, () -> composite.record(null));
        assertThrows(IllegalArgumentException.class, () -> composite.record("  "));
    }

    // ---- The registration surface ----

    @Test
    void namesAreReportedInRegistrationOrder() {
        MultiDofMechanismPlant composite = new Deposit().asComposite();
        assertEquals(Arrays.asList("slide", "pivot", "ejector", "latch"), composite.getDofNames(),
                "the log tree lists the degrees of freedom the way the human declared them");
        assertEquals(4, composite.getDofCount());
    }

    @Test
    void aDegreeOfFreedomCanBeReachedForWhatTheSharedSurfaceDoesNotCarry() {
        Deposit deposit = new Deposit();
        MultiDofMechanismPlant composite = deposit.asComposite();
        assertSame(deposit.slidePlant, composite.getDof("slide"),
                "the season layer's own typed reference and the composite's are the same object");
    }

    @Test
    void anUnknownDegreeOfFreedomIsAnErrorRatherThanAZero() {
        MultiDofMechanismPlant composite = new Deposit().asComposite();
        assertThrows(IllegalArgumentException.class, () -> composite.getPosition("wrist"));
        assertThrows(IllegalArgumentException.class, () -> composite.getVelocity("wrist"));
        assertThrows(IllegalArgumentException.class, () -> composite.getDof("wrist"));
    }

    @Test
    void aNameThatWouldCorruptTheLogTreeIsRejected() {
        Deposit deposit = new Deposit();
        MultiDofMechanismPlant.Builder builder = MultiDofMechanismPlant.builder();
        // A slash would fabricate an extra level of nesting and the key would land somewhere else.
        assertThrows(IllegalArgumentException.class,
                () -> builder.addDof("slide/inner", deposit.slidePlant, "In"));
        assertThrows(IllegalArgumentException.class, () -> builder.addDof("  ", deposit.slidePlant, "In"));
        assertThrows(IllegalArgumentException.class, () -> builder.addDof("slide", null, "In"));
        assertThrows(IllegalArgumentException.class, () -> builder.addDof("slide", deposit.slidePlant, " "));
        assertThrows(IllegalArgumentException.class,
                () -> builder.addDof("slide", deposit.slidePlant, "In/s"));
    }

    @Test
    void twoDegreesOfFreedomCannotShareAName() {
        Deposit deposit = new Deposit();
        MultiDofMechanismPlant.Builder builder = MultiDofMechanismPlant.builder()
                .addDof("slide", deposit.slidePlant, "In");
        assertThrows(IllegalArgumentException.class,
                () -> builder.addDof("slide", deposit.pivotPlant, "Rad"),
                "two keys with the same name would silently overwrite each other in the log");
    }

    @Test
    void onePlantCannotBeRegisteredAsTwoDegreesOfFreedom() {
        // A copy-paste mistake that is silent without this guard: the shared plant would be ticked twice
        // per update(), so it would move at double speed while the degree of freedom that was meant to be
        // there never moved, and both log keys would carry the same number.
        Deposit deposit = new Deposit();
        MultiDofMechanismPlant.Builder builder = MultiDofMechanismPlant.builder()
                .addDof("slide", deposit.slidePlant, "In");
        assertThrows(IllegalArgumentException.class,
                () -> builder.addDof("ejector", deposit.slidePlant, "Rev"),
                "the same plant under two names would be ticked twice per update");
    }

    @Test
    void aMechanismWithNoDegreesOfFreedomIsAWiringMistake() {
        assertThrows(IllegalArgumentException.class, () -> MultiDofMechanismPlant.builder().build());
    }

    // ---- Assertions ----

    /** Assert the RLOG carried {@code key}, and that the number under it is the plant's own reading. */
    private static void assertLoggedValue(Map<String, String> frame, String key, double expected) {
        String logged = frame.get("RealOutputs/" + key);
        assertNotNull(logged,
                "the RLOG must carry RealOutputs/" + key + "; it carried " + frame.keySet());
        assertEquals(expected, Double.parseDouble(logged), 1e-9,
                key + " must carry its own degree of freedom's number, not another's");
    }

    /** Assert every value differs from every other, so a mis-wired key cannot pass by coincidence. */
    private static void assertDistinct(double... values) {
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                assertTrue(Math.abs(values[i] - values[j]) > 1e-6,
                        "the degrees of freedom must reach different positions for this test to mean "
                                + "anything; entries " + i + " and " + j + " were both " + values[i]);
            }
        }
    }

    // ---- Fixtures ----

    /**
     * V0's Deposit, built twice per test: the four actuators, the four plants that model them, and one
     * command schedule that drives all four. Nothing here is a Deposit subsystem — that is work area P
     * and building it would be a defect (rule 1); this is only the shape the composite has to express.
     */
    private static final class Deposit {
        final FakeMotor slideMotor = new FakeMotor(1000.0, 2.0);
        final FakeServo pivotServo = new FakeServo();
        final FakeCRServo ejectServo = new FakeCRServo();
        final FakeServo latchServo = new FakeServo();

        final Mechanism1DofPlant slidePlant = new Mechanism1DofPlant(slideMotor, SLIDE);
        final PositionalServoPlant pivotPlant = new PositionalServoPlant(pivotServo, PIVOT);
        final Mechanism1DofPlant ejectorPlant = new Mechanism1DofPlant(ejectServo, EJECTOR);
        final PositionalServoPlant latchPlant = new PositionalServoPlant(latchServo, LATCH);

        MultiDofMechanismPlant asComposite() {
            return MultiDofMechanismPlant.builder()
                    .addDof("slide", slidePlant, "In")
                    .addDof("pivot", pivotPlant, "Rad")
                    .addDof("ejector", ejectorPlant, "Rev")
                    .addDof("latch", latchPlant, "Rad")
                    .build();
        }

        /**
         * One scripted deposit cycle, written as a function of the tick index so both copies of the
         * mechanism get an identical command schedule with no shared state between them: extend, aim,
         * eject, unlatch, then reverse out.
         */
        void command(int tick) {
            slideMotor.setPower(tick < 120 ? 1.0 : -0.4);
            pivotServo.setPosition(tick < 60 ? 0.0 : 0.8);
            ejectServo.setPower(tick >= 100 && tick < 160 ? 1.0 : 0.0);
            latchServo.setPosition(tick >= 95 ? 1.0 : 0.0);
        }
    }

    /** ScenarioRunner records the config's class name as metadata and reads nothing else from it. */
    private static final class TestRobotConfig implements SimRobotConfig {
        @Override public String[] driveMotorNames() { return new String[] {"FL", "FR", "BL", "BR"}; }
        @Override public String odometryName() { return "octoquad"; }
        @Override public DrivetrainSimConfig drivetrain() {
            return new DrivetrainSimConfig() {
                @Override public double trackWidth() { return 11.27362; }
                @Override public double wheelBase() { return 11.50976; }
                @Override public double wheelRadius() { return 1.88976; }
                @Override public double ticksPerInch() { return 100.0; }
                @Override public double maxVelocityTicksPerSecond() { return 9300.0; }
                @Override public double maxAccel() { return 1.8; }
                @Override public double fieldHalfWidth() { return 72.0; }
                @Override public double fieldHalfHeight() { return 72.0; }
            };
        }
    }
}
