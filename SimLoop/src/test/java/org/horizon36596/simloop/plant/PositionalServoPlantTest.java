package org.horizon36596.simloop.plant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.horizon36596.simloop.config.PositionalServoSimConfig;
import org.horizon36596.simloop.fakehardware.FakeServo;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PositionalServoPlant}, split by tier (domain R9):
 *
 * <ul>
 *   <li><b>INVARIANT-tier</b> — the B13v Contract clause 4 ("the drawn pose is a pure function of plant
 *       state, never a commanded value") and domain R6: (1) the joint never reaches the commanded position
 *       in the same tick it is commanded — there is real lag between command and arrival; (2) position
 *       never passes the end stops under any command; (3) replay is deterministic (R5). These hold under
 *       any redesign of the model and may never be retired.</li>
 *   <li><b>IMPLEMENTATION-tier</b> — pins THIS model's specific choices: the exact-exponential approach
 *       (and the deltaTime-independence it buys), the travel-rate ceiling, starting at the servo's current
 *       command, and the fail-safe handling of a bad tick. A different-but-correct lagging servo model
 *       could legitimately fail these; retireable via a reviewed redesign.</li>
 * </ul>
 *
 * The plant is driven through a {@link FakeServo} exactly as unchanged robot code would command it
 * ({@code setPosition}); time is the injected tick {@code deltaTime}, never a wall-clock (R5).
 */
class PositionalServoPlantTest {

    private static final double DT = 0.02; // 50 Hz sim tick

    /** An arm pivot in radians: 0 rad at command 0, 90° at command 1, with hard stops just outside. */
    private static PositionalServoSimConfig config(double timeConstant, double maxSpeed,
                                                   double minPosition, double maxPosition,
                                                   double positionAtCommandZero,
                                                   double positionAtCommandOne) {
        return new PositionalServoSimConfig() {
            @Override public double timeConstant() { return timeConstant; }
            @Override public double maxSpeed() { return maxSpeed; }
            @Override public double minPosition() { return minPosition; }
            @Override public double maxPosition() { return maxPosition; }
            @Override public double positionAtCommandZero() { return positionAtCommandZero; }
            @Override public double positionAtCommandOne() { return positionAtCommandOne; }
        };
    }

    /** The common fixture: a 0 → 1.5 rad pivot, fast enough that the rate cap does not dominate. */
    private static PositionalServoSimConfig pivotConfig() {
        return config(0.15, 20.0, 0.0, 1.5, 0.0, 1.5);
    }

    /**
     * The same pivot, but with the two imperfections a real servo has: a command deadband and a command
     * quantum, both in command units of the servo's {@code [0, 1]} sweep. 0.05 command units is 0.075 rad
     * on this pivot.
     */
    private static PositionalServoSimConfig imperfectConfig(double commandDeadband,
                                                            double commandQuantum) {
        return new PositionalServoSimConfig() {
            @Override public double timeConstant() { return 0.15; }
            @Override public double maxSpeed() { return 20.0; }
            @Override public double minPosition() { return 0.0; }
            @Override public double maxPosition() { return 1.5; }
            @Override public double positionAtCommandZero() { return 0.0; }
            @Override public double positionAtCommandOne() { return 1.5; }
            @Override public double commandDeadband() { return commandDeadband; }
            @Override public double commandQuantum() { return commandQuantum; }
        };
    }

    /** Runs the plant long enough for everything first-order about it to have finished moving. */
    private static void settle(PositionalServoPlant plant) {
        for (int i = 0; i < 500; i++) {
            plant.update(DT);
        }
    }

    /**
     * Seeds a joint at {@code startPosition} (mechanism units), commands the servo once, lets it settle,
     * and returns where it came to rest. The comparison these tests care about is between two configs, so
     * everything else about the run has to be identical.
     */
    private static double restAfterCommanding(PositionalServoSimConfig config, double startPosition,
                                               double command) {
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, config);
        plant.setPosition(startPosition);
        servo.setPosition(command);
        settle(plant);
        return plant.getPosition();
    }

    // ==== INVARIANT-tier: there is lag between command and arrival (Contract clause 4) ====

    @Test
    void theJointDoesNotArriveInTheTickItIsCommanded() {
        // This is the whole reason the class exists. FakeServo reports the new command instantly (matching
        // the SDK); the mechanism must not.
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, pivotConfig());

        servo.setPosition(1.0);
        assertEquals(1.0, servo.getPosition(), 0.0, "precondition: the servo reports the command instantly");

        plant.update(DT);
        assertTrue(plant.getPosition() < 1.5 * 0.5,
                "one 20 ms tick must cover well under half the sweep, saw " + plant.getPosition());
        assertNotEquals(1.5, plant.getPosition(),
                "the mechanism must not teleport to the commanded position");
    }

    @Test
    void itDoesEventuallyArriveAndThenStaysPut() {
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, pivotConfig());

        servo.setPosition(1.0);
        for (int i = 0; i < 200; i++) { // 4 s, many time constants
            plant.update(DT);
        }
        assertEquals(1.5, plant.getPosition(), 1e-6, "must settle at the commanded position");
        assertEquals(0.0, plant.getVelocity(), 1e-6, "and stop moving once it is there");
    }

    @Test
    void itNeverOvershootsTheCommandedPosition() {
        // A first-order approach has no overshoot; if this ever fails, the model has grown a second pole.
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, pivotConfig());

        servo.setPosition(1.0);
        for (int i = 0; i < 200; i++) {
            plant.update(DT);
            assertTrue(plant.getPosition() <= 1.5 + 1e-12,
                    "must approach without overshoot, saw " + plant.getPosition());
        }
    }

    // ==== INVARIANT-tier: hard end stops under adversarial commands ====

    @Test
    void aCommandPastTheEndStopParksAtTheEndStop() {
        // Servo sweep maps 0 → 2.0 rad but the arm hits a hard stop at 1.0 rad.
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, config(0.15, 20.0, 0.0, 1.0, 0.0, 2.0));

        servo.setPosition(1.0);
        for (int i = 0; i < 300; i++) {
            plant.update(DT);
            assertTrue(plant.getPosition() <= 1.0,
                    "position must never pass maxPosition, saw " + plant.getPosition());
        }
        assertEquals(1.0, plant.getPosition(), 1e-9, "must sit on the hard stop");
        assertEquals(0.0, plant.getVelocity(), 0.0,
                "a joint parked against a stop must report zero speed even while still commanded past it");
        assertEquals(1.0, plant.getCommandedPosition(), 1e-9,
                "the reported command is clamped to the stops too — the arm cannot be asked to be at 2.0");
    }

    @Test
    void aCommandBelowTheLowEndStopParksAtTheLowEndStop() {
        FakeServo servo = new FakeServo();
        // Sweep maps command 0 → -1.0 rad, but the arm cannot go below -0.3 rad.
        PositionalServoPlant plant = new PositionalServoPlant(servo, config(0.15, 20.0, -0.3, 1.0, -1.0, 1.0));

        servo.setPosition(0.0);
        for (int i = 0; i < 300; i++) {
            plant.update(DT);
            assertTrue(plant.getPosition() >= -0.3,
                    "position must never pass minPosition, saw " + plant.getPosition());
        }
        assertEquals(-0.3, plant.getPosition(), 1e-9, "must sit on the low hard stop");
    }

    // ==== INVARIANT-tier: deterministic replay (R5) ====

    @Test
    void identicalCommandSequencesGiveBitIdenticalPositions() {
        double[] first = runScriptedSweep();
        double[] second = runScriptedSweep();
        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i], second[i], 0.0,
                    "replay must be bit-identical at tick " + i + " (domain R5)");
        }
    }

    private static double[] runScriptedSweep() {
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, pivotConfig());
        double[] positions = new double[120];
        for (int i = 0; i < positions.length; i++) {
            servo.setPosition(i < 60 ? 1.0 : 0.2);
            plant.update(DT);
            positions[i] = plant.getPosition();
        }
        return positions;
    }

    // ==== IMPLEMENTATION-tier: the exact-exponential approach ====

    @Test
    void oneTimeConstantCoversAboutSixtyThreePercentOfTheSweep() {
        // Pins the first-order pole itself: after tau, ~1 - 1/e of the way there.
        FakeServo servo = new FakeServo();
        PositionalServoSimConfig config = config(0.15, 1000.0, 0.0, 1.5, 0.0, 1.5); // rate cap far away
        PositionalServoPlant plant = new PositionalServoPlant(servo, config);

        servo.setPosition(1.0);
        int ticksInOneTimeConstant = (int) StrictMath.round(0.15 / DT); // 7.5 -> 8 ticks
        for (int i = 0; i < ticksInOneTimeConstant; i++) {
            plant.update(DT);
        }
        double fractionOfSweep = plant.getPosition() / 1.5;
        assertEquals(1.0 - 1.0 / StrictMath.E, fractionOfSweep, 0.03,
                "after one time constant the joint should be ~63% of the way there, saw " + fractionOfSweep);
    }

    @Test
    void splittingATickInHalfLandsInTheSamePlace() {
        // The exact exponential is what makes this true, and it is why the log replays the same at any
        // tick rate. Only holds while the travel-rate cap is not binding, so maxSpeed is set far away.
        PositionalServoSimConfig config = config(0.15, 1000.0, 0.0, 1.5, 0.0, 1.5);

        FakeServo coarseServo = new FakeServo();
        PositionalServoPlant coarse = new PositionalServoPlant(coarseServo, config);
        coarseServo.setPosition(1.0);
        for (int i = 0; i < 20; i++) {
            coarse.update(DT);
        }

        FakeServo fineServo = new FakeServo();
        PositionalServoPlant fine = new PositionalServoPlant(fineServo, config);
        fineServo.setPosition(1.0);
        for (int i = 0; i < 40; i++) {
            fine.update(DT / 2.0);
        }

        assertEquals(coarse.getPosition(), fine.getPosition(), 1e-9,
                "a held command must land in the same place whatever the tick size");
    }

    // ==== IMPLEMENTATION-tier: the travel-rate ceiling ====

    @Test
    void theTravelRateCeilingLimitsHowFarOneTickCanMove() {
        // maxSpeed 1 rad/s over a 20 ms tick is 0.02 rad, far less than the exponential would take.
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, config(0.15, 1.0, 0.0, 1.5, 0.0, 1.5));

        servo.setPosition(1.0);
        plant.update(DT);
        assertEquals(1.0 * DT, plant.getPosition(), 1e-12,
                "a large commanded jump must slew at maxSpeed, not take the full exponential step");
        assertEquals(1.0, plant.getVelocity(), 1e-9, "and report exactly the rate ceiling as its speed");
    }

    @Test
    void theRateCeilingStopsBindingOnceTheJointIsClose() {
        // The cap is a ceiling, not a constant rate: near the target the exponential is smaller and wins,
        // so the joint eases in rather than arriving at full speed.
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, config(0.15, 1.0, 0.0, 1.5, 0.0, 1.5));

        servo.setPosition(1.0);
        for (int i = 0; i < 400; i++) {
            plant.update(DT);
        }
        assertEquals(1.5, plant.getPosition(), 1e-6, "must still arrive");
        assertTrue(plant.getVelocity() < 1.0,
                "and must be easing in, not still slewing at the ceiling, saw " + plant.getVelocity());
    }

    // ==== IMPLEMENTATION-tier: the command → position map ====

    @Test
    void aServoMountedBackwardsIsJustTheEndpointsInTheOtherOrder() {
        // positionAtCommandZero > positionAtCommandOne: command 1.0 drives the arm DOWN.
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, config(0.15, 20.0, 0.0, 1.5, 1.5, 0.0));

        assertEquals(1.5, plant.getPosition(), 1e-12, "command 0 must map to the high end of the sweep");
        servo.setPosition(1.0);
        for (int i = 0; i < 200; i++) {
            plant.update(DT);
        }
        assertEquals(0.0, plant.getPosition(), 1e-6, "command 1 must drive it to the low end");
    }

    @Test
    void scaleRangeNarrowsThePhysicalSweep() {
        // scaleRange(0.2, 0.6) means a full [0,1] request only moves the horn across 40% of its travel, so
        // the mechanism only reaches 40% of the modelled sweep. Reading getScaledPosition() is what makes
        // this come out right; getPosition() would undo the scaling and draw the arm too far.
        FakeServo servo = new FakeServo();
        servo.scaleRange(0.2, 0.6);
        PositionalServoPlant plant = new PositionalServoPlant(servo, config(0.15, 20.0, 0.0, 1.5, 0.0, 1.5));

        servo.setPosition(1.0);
        for (int i = 0; i < 200; i++) {
            plant.update(DT);
        }
        assertEquals(0.6 * 1.5, plant.getPosition(), 1e-6,
                "a scaled servo must only reach the scaled fraction of the sweep");
    }

    @Test
    void theCommandedPositionIsReportedSeparatelyFromWhereTheJointActuallyIs() {
        // The gap between the two is the lag itself, and logging both is how a human sees it.
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, pivotConfig());

        servo.setPosition(1.0);
        plant.update(DT);
        assertEquals(1.5, plant.getCommandedPosition(), 1e-12, "the command is immediate");
        assertTrue(plant.getPosition() < plant.getCommandedPosition(),
                "the joint must still be behind it");
    }

    // ==== IMPLEMENTATION-tier: starting state and fail-safes ====

    @Test
    void itStartsWhereTheServoIsAlreadyCommandedRatherThanSweepingAtStartup() {
        FakeServo servo = new FakeServo();
        servo.setPosition(0.5);
        PositionalServoPlant plant = new PositionalServoPlant(servo, pivotConfig());

        assertEquals(0.75, plant.getPosition(), 1e-12,
                "a servo powers up holding its command; the plant must not fabricate a sweep at t=0");
        assertEquals(0.0, plant.getVelocity(), 0.0, "and must start at rest");
    }

    @Test
    void seedingAPositionClampsToTheEndStopsAndStopsMotion() {
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, pivotConfig());

        plant.setPosition(99.0);
        assertEquals(1.5, plant.getPosition(), 0.0, "a seed past the stop must clamp, not teleport outside");
        plant.setPosition(-99.0);
        assertEquals(0.0, plant.getPosition(), 0.0, "same at the low stop");
        assertThrows(IllegalArgumentException.class, () -> plant.setPosition(Double.NaN),
                "a NaN seed is a caller bug and must fail loud, not poison the state");
    }

    @Test
    void nonFiniteOrNonPositiveDeltaTimeIsANoOp() {
        // Pins THIS design's fail-safe (a bad tick means no time passed). A different plant could fail
        // loud instead and still honour the end-stop invariant.
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, pivotConfig());
        plant.setPosition(0.4);
        servo.setPosition(1.0);

        plant.update(Double.NaN);
        plant.update(-0.02);
        plant.update(0.0);
        assertEquals(0.4, plant.getPosition(), 0.0, "bad ticks must not move (or corrupt) position");
    }

    // ==== INVARIANT-tier: the glue params are validated up front ====

    @Test
    void badConfigurationFailsAtConstruction() {
        FakeServo servo = new FakeServo();
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalServoPlant(null, pivotConfig()),
                "a plant with no servo has nothing to read a command from");
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalServoPlant(servo, config(0.0, 20.0, 0.0, 1.5, 0.0, 1.5)),
                "a zero time constant is an instantaneous joint, which is the bug this class fixes");
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalServoPlant(servo, config(0.15, 0.0, 0.0, 1.5, 0.0, 1.5)),
                "a zero travel rate is a joint that never moves");
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalServoPlant(servo, config(0.15, 20.0, 1.5, 1.5, 0.0, 1.5)),
                "end stops must leave somewhere to move");
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalServoPlant(servo, config(0.15, 20.0, 0.0, 1.5, 0.7, 0.7)),
                "a sweep that maps to one position is a joint that cannot move");
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalServoPlant(servo, config(Double.NaN, 20.0, 0.0, 1.5, 0.0, 1.5)),
                "a non-finite config value must fail loud rather than poison the state later");
    }

    // ==== INVARIANT-tier: the two imperfections that make a servo joint hard to aim ====

    /**
     * Contract: both imperfections default to off, so every config written before they existed behaves
     * exactly as it did. A joint that only has to get roughly somewhere never has to think about them.
     */
    @Test
    void aServoWithNeitherImperfectionStillArrivesExactly() {
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, pivotConfig());
        servo.setPosition(0.4);

        settle(plant);

        assertEquals(0.6, plant.getPosition(), 1e-9,
                "with no deadband and no quantum the joint converges on the commanded position");
    }

    /**
     * Contract: the joint stops inside the deadband and stays there. Invariant tier — this is the whole
     * reason the parameter exists.
     *
     * <p>A real servo compares the commanded pulse against its own pot and does not drive at all while the
     * two are within its deadband, so the joint parks short of the target with an error that no amount of
     * further time removes. A servo modelled without one converges every time and makes an aiming problem
     * look solved.
     */
    @Test
    void theJointStopsInsideTheDeadbandAndThenStaysThere() {
        // Measured against the same servo with no deadband rather than against the target. A first-order
        // approach misses its target by about one ulp forever, so "did not reach the target" is true of a
        // perfect servo too and would pass with the deadband deleted.
        double perfectRest = restAfterCommanding(imperfectConfig(0.0, 0.0), 0.0, 1.0);
        double deadbandedRest = restAfterCommanding(imperfectConfig(0.05, 0.0), 0.0, 1.0);

        assertTrue(perfectRest - deadbandedRest > 0.02,
                "a deadbanded servo must stop measurably short of where a perfect one stops: perfect "
                        + perfectRest + ", deadbanded " + deadbandedRest);
        assertTrue(deadbandedRest >= 1.5 - 0.05 * 1.5,
                "but it must get inside the deadband, not stall early: rested at " + deadbandedRest);

        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, imperfectConfig(0.05, 0.0));
        servo.setPosition(1.0);
        settle(plant);
        double rest = plant.getPosition();
        assertEquals(0.0, plant.getVelocity(), 0.0, "it must report itself stopped, not creeping");
        settle(plant);
        assertEquals(rest, plant.getPosition(), 0.0,
                "and another five hundred ticks must not close the gap — that is what a steady-state"
                        + " error is");
    }

    /**
     * Contract: where the joint rests depends on which way it arrived. Invariant tier, and the sharpest
     * consequence of the deadband.
     *
     * <p>Approaching one target from below leaves the joint short of it; from above, past it. So the error
     * flips sign with the direction of travel, which is exactly what defeats a controller that assumes its
     * plant is symmetric, and exactly what a turret does when the drivetrain rotates one way and then the
     * other.
     */
    @Test
    void whereTheJointRestsDependsOnWhichWayItArrived() {
        double target = 0.75;
        double fromBelow = restAfterCommanding(imperfectConfig(0.05, 0.0), 0.0, 0.5);
        double fromAbove = restAfterCommanding(imperfectConfig(0.05, 0.0), 1.5, 0.5);

        assertTrue(fromAbove - fromBelow > 0.05,
                "the same command must have two clearly different resting places, not two that differ by"
                        + " rounding: from below " + fromBelow + ", from above " + fromAbove);
        assertTrue(fromBelow < target && fromAbove > target,
                "and they must straddle the target: from below " + fromBelow + ", from above " + fromAbove);
        assertTrue(Math.abs(fromBelow - target) <= 0.05 * 1.5 + 1e-9
                        && Math.abs(fromAbove - target) <= 0.05 * 1.5 + 1e-9,
                "both must be inside the deadband, or the joint stalled for some other reason");

        double perfectFromBelow = restAfterCommanding(imperfectConfig(0.0, 0.0), 0.0, 0.5);
        double perfectFromAbove = restAfterCommanding(imperfectConfig(0.0, 0.0), 1.5, 0.5);
        assertEquals(perfectFromBelow, perfectFromAbove, 1e-9,
                "a servo with no deadband must land in the same place whichever way it came — the"
                        + " direction-dependence is the deadband's doing, not the approach's");
    }

    /**
     * Contract: the command is rounded to the quantum, so only a grid of positions can be reached.
     * Invariant tier.
     *
     * <p>This is a different floor from the deadband: it limits what can be <i>asked for</i> rather than
     * how much of the request is ignored. With no deadband the joint still converges exactly — just onto
     * the wrong point.
     */
    @Test
    void onlyAGridOfPositionsCanBeAskedFor() {
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, imperfectConfig(0.0, 0.25));
        servo.setPosition(0.6);

        settle(plant);

        assertEquals(0.75, plant.getPosition(), 1e-9,
                "0.6 is not on the 0.25 grid, so the servo is really driven to 0.5 -> 0.75 rad");
        assertNotEquals(0.9, plant.getPosition(),
                "and never to the 0.9 rad the caller literally asked for");
    }

    /**
     * Contract: rounding is to the NEAREST step, not downward. Implementation tier.
     *
     * <p>Truncating would bias every command the same way, which reads as a mounting or calibration offset
     * and would send somebody hunting for a mechanical fault that is not there.
     */
    @Test
    void theCommandIsRoundedToTheNearestStepNotTruncated() {
        FakeServo low = new FakeServo();
        PositionalServoPlant justBelowHalfway = new PositionalServoPlant(low, imperfectConfig(0.0, 0.25));
        low.setPosition(0.6);
        settle(justBelowHalfway);

        FakeServo high = new FakeServo();
        PositionalServoPlant justAboveHalfway = new PositionalServoPlant(high, imperfectConfig(0.0, 0.25));
        high.setPosition(0.65);
        settle(justAboveHalfway);

        assertEquals(0.75, justBelowHalfway.getPosition(), 1e-9, "0.6 rounds down to 0.5");
        assertEquals(1.125, justAboveHalfway.getPosition(), 1e-9, "0.65 rounds up to 0.75");
    }

    /**
     * Contract: {@code getCommandedPosition()} reports the quantized command, not the raw request.
     * Implementation tier — it is what gets logged beside the actual position, and the gap between them
     * is read as lag. A raw request would put the command resolution into that gap and make it look like a
     * control problem.
     */
    @Test
    void theReportedCommandIsTheOneTheServoIsReallyActingOn() {
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, imperfectConfig(0.0, 0.25));
        servo.setPosition(0.6);

        assertEquals(0.75, plant.getCommandedPosition(), 1e-9,
                "the servo was really asked for 0.5, which is 0.75 rad on this pivot");
    }

    /**
     * Contract: an imperfect servo is still deterministic. Invariant tier (domain R5) — neither the
     * rounding nor the deadband may introduce a path-dependence the replay cannot reproduce.
     */
    @Test
    void anImperfectServoReplaysIdentically() {
        assertArrayEquals(scriptedImperfectSweep(), scriptedImperfectSweep(),
                "the same commands at the same ticks must give bit-identical positions");
    }

    private static double[] scriptedImperfectSweep() {
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, imperfectConfig(0.03, 0.01));
        double[] positions = new double[60];
        for (int tick = 0; tick < positions.length; tick++) {
            servo.setPosition(tick < 20 ? 0.83 : (tick < 40 ? 0.17 : 0.51));
            plant.update(DT);
            positions[tick] = plant.getPosition();
        }
        return positions;
    }

    /**
     * Contract: rounding to the nearest step may land outside the servo sweep, and the result is clamped
     * back into it. Implementation tier.
     *
     * <p>A command of 1.0 with a quantum of 0.4 rounds to 1.2, and there is no such pulse. Left unclamped
     * it would drive the target past the servo end of the sweep, which matters because the end stops are
     * allowed to sit outside the command endpoints.
     */
    @Test
    void aCommandThatRoundsPastTheEndOfTheSweepIsClampedBackIntoIt() {
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, imperfectConfig(0.0, 0.4));
        servo.setPosition(1.0);

        assertEquals(1.5, plant.getCommandedPosition(), 1e-9,
                "1.0 rounds to 1.2, which is not a pulse; the servo can only be asked for its own end");
        settle(plant);
        assertEquals(1.5, plant.getPosition(), 1e-9, "and the joint goes to that end, not past it");
    }

    /**
     * Contract: the deadband is a fraction of the servo's command sweep, not a distance in mechanism
     * units. Invariant tier.
     *
     * <p>The fixture the other deadband tests use has a sweep of 1.5 rad, so a deadband compared in the
     * wrong space lands only a little off and hides inside a loose bound. This one sweeps ten units, where
     * the two readings differ by a factor of ten and cannot be confused.
     */
    @Test
    void theDeadbandIsAFractionOfTheCommandSweepNotADistanceInMechanismUnits() {
        PositionalServoSimConfig wideSweep = new PositionalServoSimConfig() {
            @Override public double timeConstant() { return 0.15; }
            @Override public double maxSpeed() { return 200.0; }
            @Override public double minPosition() { return 0.0; }
            @Override public double maxPosition() { return 10.0; }
            @Override public double positionAtCommandZero() { return 0.0; }
            @Override public double positionAtCommandOne() { return 10.0; }
            @Override public double commandDeadband() { return 0.05; }
        };

        double rest = restAfterCommanding(wideSweep, 0.0, 1.0);
        double error = 10.0 - rest;

        assertTrue(error > 0.2,
                "0.05 of a ten-unit sweep is half a unit, so the joint must stop about half a unit short."
                        + " Stopping " + error + " short means the deadband was read as mechanism units");
        assertTrue(error <= 0.5 + 1e-9,
                "and it must still be inside the deadband, which is 0.5 units here: stopped " + error
                        + " short");
    }

    /**
     * Contract: the deadband works on a servo mounted backwards. Invariant tier.
     *
     * <p>The config deliberately allows {@code positionAtCommandZero > positionAtCommandOne}, so the span
     * the deadband is measured against is negative. A sign slip there would either freeze the joint at once
     * or never stop it, and neither shows up on a forward-mounted fixture.
     */
    @Test
    void aBackwardsMountedServoStopsInsideTheDeadbandToo() {
        PositionalServoSimConfig backwards = reversedConfig(0.05);
        PositionalServoSimConfig backwardsButPerfect = reversedConfig(0.0);

        double rest = restAfterCommanding(backwards, 1.5, 1.0);
        double perfectRest = restAfterCommanding(backwardsButPerfect, 1.5, 1.0);

        assertTrue(rest - perfectRest > 0.005,
                "the deadbanded joint must stop short of where the perfect one stops: deadbanded " + rest
                        + ", perfect " + perfectRest);
        assertTrue(rest <= 0.05 * 1.5 + 1e-9,
                "and still inside the deadband, measured down the reversed sweep: rested at " + rest);
    }

    /** A pivot wired the other way round: command 0 is 1.5 rad and command 1 is 0 rad. */
    private static PositionalServoSimConfig reversedConfig(double commandDeadband) {
        return new PositionalServoSimConfig() {
            @Override public double timeConstant() { return 0.15; }
            @Override public double maxSpeed() { return 20.0; }
            @Override public double minPosition() { return 0.0; }
            @Override public double maxPosition() { return 1.5; }
            @Override public double positionAtCommandZero() { return 1.5; }
            @Override public double positionAtCommandOne() { return 0.0; }
            @Override public double commandDeadband() { return commandDeadband; }
        };
    }

    /**
     * Contract: with both imperfections at once the joint lands where both of them say it should.
     * Invariant tier — a real servo has both, and the two are applied in sequence, so this is the case
     * the season actually configures.
     *
     * <p>A command of 0.6 with a quantum of 0.25 is really a command of 0.5, which is 0.75 rad. The joint
     * then stops short of that by up to the deadband. So the answer is neither the 0.9 rad that was asked
     * for nor the 0.75 rad the servo was really given.
     */
    @Test
    void theTwoImperfectionsComposeRatherThanOneWinning() {
        double rest = restAfterCommanding(imperfectConfig(0.05, 0.25), 0.0, 0.6);

        assertTrue(rest >= 0.675 - 1e-9,
                "the joint must get inside the deadband around the quantized target: rested at " + rest);
        assertTrue(rest < 0.75 - 0.02,
                "but stop measurably short of it, because the deadband still applies: rested at " + rest);
        assertTrue(Math.abs(rest - 0.9) > 0.1,
                "and nowhere near the 0.9 rad the caller literally asked for: rested at " + rest);
    }

    /**
     * Contract: a hard end stop inside the servo's sweep still stops the joint exactly, deadband or not.
     * Implementation tier.
     *
     * <p>Here the command can never be satisfied — the joint is clamped at 1.0 rad while the command
     * asks for 1.5 — so the deadband test never passes and the plant keeps stepping. It must settle
     * against the stop rather than creep or chatter.
     */
    @Test
    void anEndStopInsideTheSweepStillHoldsTheJointWithADeadband() {
        PositionalServoSimConfig stopBeforeTheSweepEnds = new PositionalServoSimConfig() {
            @Override public double timeConstant() { return 0.15; }
            @Override public double maxSpeed() { return 20.0; }
            @Override public double minPosition() { return 0.0; }
            @Override public double maxPosition() { return 1.0; }
            @Override public double positionAtCommandZero() { return 0.0; }
            @Override public double positionAtCommandOne() { return 1.5; }
            @Override public double commandDeadband() { return 0.05; }
        };
        FakeServo servo = new FakeServo();
        PositionalServoPlant plant = new PositionalServoPlant(servo, stopBeforeTheSweepEnds);
        plant.setPosition(0.0);
        servo.setPosition(1.0);

        settle(plant);

        assertEquals(1.0, plant.getPosition(), 1e-9, "the joint parks on the hard stop, not short of it");
        assertEquals(0.0, plant.getVelocity(), 0.0, "and reports itself stopped rather than chattering");
    }

    @Test
    void aDeadbandOrQuantumWiderThanTheSweepIsRejected() {
        FakeServo servo = new FakeServo();
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalServoPlant(servo, imperfectConfig(-0.01, 0.0)),
                "a negative deadband is not a servo");
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalServoPlant(servo, imperfectConfig(1.0, 0.0)),
                "a deadband as wide as the whole sweep is a joint that can only sit where it started");
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalServoPlant(servo, imperfectConfig(0.0, -0.01)),
                "a negative quantum is not a step size");
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalServoPlant(servo, imperfectConfig(0.0, 1.0)),
                "a quantum as wide as the sweep leaves two reachable positions");
        assertThrows(IllegalArgumentException.class,
                () -> new PositionalServoPlant(servo, imperfectConfig(Double.NaN, 0.0)),
                "a non-finite deadband must fail loud");
    }
}
