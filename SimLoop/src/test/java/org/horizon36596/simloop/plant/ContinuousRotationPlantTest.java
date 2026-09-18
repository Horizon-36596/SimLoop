package org.horizon36596.simloop.plant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qualcomm.robotcore.hardware.DcMotorSimple;
import org.horizon36596.simloop.config.ContinuousRotationSimConfig;
import org.horizon36596.simloop.config.MechanismSimConfig;
import org.horizon36596.simloop.fakehardware.FakeCRServo;
import org.horizon36596.simloop.sim.FakeTimer;

import org.junit.jupiter.api.Test;

/**
 * INVARIANT-tier (domain R9) — B13 Contract clause 3, motion half: <b>the plant behind a
 * continuous-rotation ejector turns power into motion on {@code FakeTimer}, never on a wall clock</b>
 * (domain R5). The device half — that {@link FakeCRServo} matches the SDK's {@code CRServo} exactly — is
 * pinned by {@code FakeCRServoTest}.
 *
 * <p><b>Two tiers live in this file, and the split is marked per test</b> (domain R9). The tests above
 * the "implementation-tier" divider pin the Contract clause itself and hold under any redesign — they
 * would still have to pass if the ejector were modelled by a brand-new plant class tomorrow. The ones
 * below the divider pin B13's specific decision to reuse {@link Mechanism1DofPlant} with infinite end
 * limits rather than write a fourth plant; a reviewed redesign that chose differently would retire those,
 * and only those. Marking the whole class invariant would have promised that the implementation choice
 * could never be revisited, which is not a promise this batch is entitled to make.
 */
class ContinuousRotationPlantTest {

    private static final double DT = 0.02;

    /** An ejector: output revolutions, ~60 ms to spin up, 3.5 rev/s flat out. */
    private static final ContinuousRotationSimConfig EJECTOR = new ContinuousRotationSimConfig() {
        @Override public double timeConstant() { return 0.06; }
        @Override public double maxSpeed() { return 3.5; }
    };

    // ---- The clause: motion comes off the injected tick, and only off the injected tick ----

    @Test
    void travelAdvancesOnlyWhenTheSimTicks() {
        // Domain R5: the plant has no clock of its own. Wall time passing while nothing ticks must move
        // nothing, which is what makes a replay reproducible on a machine of any speed.
        FakeCRServo ejector = new FakeCRServo();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(ejector, EJECTOR);
        ejector.setPower(1.0);

        assertEquals(0.0, plant.getPosition(), 0.0, "commanding power alone must move nothing");

        // 1 ms is plenty: the point is that SOME real time passed, not how much. Kept small so the suite
        // does not burn CPU, and bounded so it cannot hang on a loaded machine.
        long before = System.nanoTime();
        while (System.nanoTime() - before < 1_000_000L) {
            // burn real time without ticking
        }

        assertEquals(0.0, plant.getPosition(), 0.0,
                "real milliseconds went by with no update(dt); the plant must not have noticed");

        plant.update(DT);
        assertTrue(plant.getPosition() > 0.0, "and one tick is what actually moves it");
    }

    @Test
    void theSameTickScheduleAlwaysProducesTheSameTravel() {
        assertArrayEquals(runEjectCycle(), runEjectCycle(), 0.0,
                "two identical runs must produce a bit-identical travel trace (domain R5)");
    }

    @Test
    void speedTracksTheSimClockAndNotTheNumberOfCalls() {
        // The velocity update is an EXACT first-order step, so the same elapsed sim time reached in
        // different-sized ticks gives the identical speed. Sampled at one time constant, where the
        // transient is steepest and a deltaTime-dependent formula would be caught.
        double coarse = speedAfter(0.02, 3);      // 0.06 s = one time constant
        double fine = speedAfter(0.005, 12);      // the same 0.06 s, four times as many ticks

        assertEquals(coarse, fine, 1e-12,
                "one time constant of sim time is one time constant of spin-up however it was ticked");
    }

    @Test
    void travelTracksTheSimClockToWithinTheIntegratorsOwnError() {
        // Travel is NOT exactly tick-size-independent, and pretending otherwise would be a lie in a test.
        // Mechanism1DofPlant integrates position as `position += velocity * dt` -- a rectangle rule -- so
        // during a transient a coarser tick under-counts the area by O(dt). It converges as the tick
        // shrinks, and at the tick rates this sim actually runs the gap is well under a percent. This is a
        // property of the plant that has been there since Batch 2.1, not something B13 introduced;
        // replay determinism (domain R5) is unaffected because a replay reuses the SAME tick schedule.
        double coarse = travelAfter(0.04, 50);   // 2.0 s of sim time
        double fine = travelAfter(0.01, 200);    // the same 2.0 s, four times as many ticks

        // Measured 2026-09-15: coarse 6.807 rev vs fine 6.852 rev, a gap of 0.045 -- about 0.66%, so the
        // 1% tolerance below sits just above the real error rather than being a round number picked to
        // make the test pass. If a change to the integrator widens this, the test should fail.
        assertEquals(coarse, fine, 0.01 * fine,
                "2 seconds of sim time is 2 seconds of travel to within the integrator's own error");
    }

    @Test
    void theFakeTimerAndThePlantAgreeOnHowMuchTimeHasPassed() {
        // The harness ticks the plant and the clock with the same dt; at full power and steady state the
        // travel is maxSpeed * elapsed, so the two are checkable against each other.
        FakeTimer timer = new FakeTimer();
        FakeCRServo ejector = new FakeCRServo();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(ejector, EJECTOR);
        ejector.setPower(1.0);

        for (int tick = 0; tick < 500; tick++) {   // 10 s, far past the 60 ms spin-up
            plant.update(DT);
            timer.advance(DT);
        }

        double spinUpLoss = EJECTOR.maxSpeed() * EJECTOR.timeConstant();  // area under the ramp
        assertEquals(EJECTOR.maxSpeed() * timer.time() - spinUpLoss, plant.getPosition(), 0.05,
                "steady-state travel is maxSpeed times the elapsed sim time, less the spin-up ramp");
    }

    // ======================================================================================
    // IMPLEMENTATION-TIER from here down (domain R9): these pin B13's choice to reuse
    // Mechanism1DofPlant with infinite end limits rather than add a fourth plant class. A reviewed
    // redesign may retire them; the invariant-tier tests above it may not.
    // ======================================================================================

    @Test
    void travelAccumulatesWithoutBoundBecauseThereIsNoHardStop() {
        FakeCRServo ejector = new FakeCRServo();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(ejector, EJECTOR);
        ejector.setPower(1.0);
        for (int tick = 0; tick < 5000; tick++) {   // 100 s of continuous running
            plant.update(DT);
        }

        assertTrue(plant.getPosition() > 300.0,
                "a shaft that keeps turning keeps accumulating travel; got " + plant.getPosition());
        assertTrue(Double.isFinite(plant.getPosition()), "and the number stays finite");
    }

    @Test
    void anEjectorNeverStallsBecauseItHasNothingToStallAgainst() {
        FakeCRServo ejector = new FakeCRServo();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(ejector, EJECTOR);
        ejector.setPower(1.0);
        for (int tick = 0; tick < 500; tick++) {
            plant.update(DT);
        }

        assertEquals(EJECTOR.maxSpeed(), plant.getVelocity(), 1e-6,
                "it holds top speed indefinitely rather than zeroing out on an end limit");
    }

    @Test
    void reversingThePowerReversesTheTravel() {
        FakeCRServo ejector = new FakeCRServo();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(ejector, EJECTOR);

        ejector.setPower(1.0);
        for (int tick = 0; tick < 100; tick++) {
            plant.update(DT);
        }
        double forward = plant.getPosition();

        ejector.setPower(-1.0);
        for (int tick = 0; tick < 100; tick++) {
            plant.update(DT);
        }

        assertTrue(plant.getPosition() < forward,
                "an ejector run backwards unwinds; there is no floor under it");
    }

    @Test
    void aReverseServoTurnsTheOtherWay() {
        // The plant applies the SDK direction contract to whatever DcMotorSimple it is handed, and a
        // CRServo is a DcMotorSimple -- which is the whole reason this needs no adapter.
        FakeCRServo ejector = new FakeCRServo();
        ejector.setDirection(DcMotorSimple.Direction.REVERSE);
        Mechanism1DofPlant plant = new Mechanism1DofPlant(ejector, EJECTOR);
        ejector.setPower(1.0);
        for (int tick = 0; tick < 100; tick++) {
            plant.update(DT);
        }

        assertTrue(plant.getPosition() < 0.0,
                "a REVERSE servo commanded positive power drives its mechanism the other way");
    }

    @Test
    void theConfigFixesBothLimitsAtInfinityAndGravityAtZero() {
        assertEquals(Double.NEGATIVE_INFINITY, EJECTOR.minPosition(), "no hard stop below");
        assertEquals(Double.POSITIVE_INFINITY, EJECTOR.maxPosition(), "no hard stop above");
        assertEquals(0.0, EJECTOR.gravityHoldPowerFraction(), 0.0,
                "a continuously rotating shaft has no height to sag from");
    }

    @Test
    void anInfiniteLimitIsAcceptedButANaNLimitIsStillRejected() {
        // The relaxation is narrow on purpose: an infinity means something ("no stop here"), a NaN means
        // a caller bug, and it would slip the Math.min/max clamp and poison position permanently.
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanism1DofPlant(new FakeCRServo(), limits(Double.NaN, 1.0)),
                "a NaN minPosition is a caller bug, not a mechanism");
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanism1DofPlant(new FakeCRServo(), limits(0.0, Double.NaN)),
                "and so is a NaN maxPosition");
    }

    @Test
    void aMechanismWithNoLowerStopCannotBeGravityLoaded() {
        // Gravity pulls toward minPosition. With no lower stop that mechanism sags forever with nothing
        // to land on, and the plant would report it as an ever-growing negative position rather than as
        // an error -- so the combination is rejected at construction.
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanism1DofPlant(new FakeCRServo(),
                        gravityLoaded(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0.3)),
                "an infinite lower limit with gravity models a mechanism that falls forever");
    }

    @Test
    void anInfiniteUpperLimitWithGravityIsFineBecauseThereIsStillAFloor() {
        // The mirror case is a real build -- a winch that can be wound up forever but bottoms out -- so
        // the guard above must not reject it.
        Mechanism1DofPlant winch = new Mechanism1DofPlant(new FakeCRServo(),
                gravityLoaded(0.0, Double.POSITIVE_INFINITY, 0.3));
        for (int tick = 0; tick < 200; tick++) {
            winch.update(DT);
        }
        assertEquals(0.0, winch.getPosition(), 0.0, "it sags onto its floor and stays there");
    }

    @Test
    void oneEndCanBeOpenWhileTheOtherIsStopped() {
        // A mechanism can genuinely have a stop on one side only -- a spool that bottoms out but can be
        // wound up forever. The relaxation has to allow that, not only the both-ends-open case.
        FakeCRServo spool = new FakeCRServo();
        Mechanism1DofPlant plant =
                new Mechanism1DofPlant(spool, limits(0.0, Double.POSITIVE_INFINITY));
        spool.setPower(-1.0);
        for (int tick = 0; tick < 200; tick++) {
            plant.update(DT);
        }

        assertEquals(0.0, plant.getPosition(), 0.0, "it stops on the end that has a stop");
        assertEquals(0.0, plant.getVelocity(), 0.0, "and stalls there, as any hardstopped mechanism does");
    }

    // ---- Fixtures ----

    /** The ejector config with its two end limits overridden, for the limit-validation tests. */
    private static MechanismSimConfig limits(final double min, final double max) {
        return new MechanismSimConfig() {
            @Override public double timeConstant() { return 0.06; }
            @Override public double maxSpeed() { return 3.5; }
            @Override public double minPosition() { return min; }
            @Override public double maxPosition() { return max; }
            @Override public double gravityHoldPowerFraction() { return 0.0; }
        };
    }

    /** The ejector config with end limits AND a gravity load overridden, for the guard tests. */
    private static MechanismSimConfig gravityLoaded(final double min, final double max, final double gravity) {
        return new MechanismSimConfig() {
            @Override public double timeConstant() { return 0.06; }
            @Override public double maxSpeed() { return 3.5; }
            @Override public double minPosition() { return min; }
            @Override public double maxPosition() { return max; }
            @Override public double gravityHoldPowerFraction() { return gravity; }
        };
    }

    /** One scripted eject: spin up, hold, stop. Returns the travel at every tick. */
    private static double[] runEjectCycle() {
        FakeTimer timer = new FakeTimer();
        FakeCRServo ejector = new FakeCRServo();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(ejector, EJECTOR);

        double[] travel = new double[300];
        for (int tick = 0; tick < travel.length; tick++) {
            ejector.setPower(tick < 200 ? 1.0 : 0.0);
            plant.update(DT);
            timer.advance(DT);
            travel[tick] = plant.getPosition();
        }
        return travel;
    }

    /** Modelled speed after {@code ticks} ticks of {@code dt} at full power. */
    private static double speedAfter(double dt, int ticks) {
        FakeCRServo ejector = new FakeCRServo();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(ejector, EJECTOR);
        ejector.setPower(1.0);
        for (int tick = 0; tick < ticks; tick++) {
            plant.update(dt);
        }
        return plant.getVelocity();
    }

    /** Travel after {@code ticks} ticks of {@code dt} at full power. */
    private static double travelAfter(double dt, int ticks) {
        FakeCRServo ejector = new FakeCRServo();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(ejector, EJECTOR);
        ejector.setPower(1.0);
        for (int tick = 0; tick < ticks; tick++) {
            plant.update(dt);
        }
        return plant.getPosition();
    }
}
