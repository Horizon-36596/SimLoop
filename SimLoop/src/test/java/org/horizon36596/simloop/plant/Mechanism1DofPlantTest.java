package org.horizon36596.simloop.plant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qualcomm.robotcore.hardware.DcMotorSimple;
import org.horizon36596.simloop.config.MechanismSimConfig;
import org.horizon36596.simloop.fakehardware.FakeMotor;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Mechanism1DofPlant}, split by tier (domain R9):
 *
 * <ul>
 *   <li><b>INVARIANT-tier</b> — the Batch 2.1 Contract; holds under ANY redesign and may never be
 *       retired: (1) position never integrates past {@code [minPosition, maxPosition]} under any motor
 *       command; (2) under a held power the velocity converges to the first-order steady-state
 *       (power × maxSpeed) with the configured time constant and position integrates from it — first-order
 *       motion, not a kinematic jump. Plus deterministic replay (R5) and the glue-param validation.</li>
 *   <li><b>IMPLEMENTATION-tier</b> — pins the CURRENT design's specific choices (exact-exponential update,
 *       stall-on-wall anti-windup, fail-safe handling of bad inputs). A different-but-correct first-order
 *       plant that honors the Contract could legitimately fail these; retireable via a reviewed redesign.</li>
 * </ul>
 *
 * The plant is driven through a motor exactly as unchanged robot code would (setPower); time is the
 * injected tick {@code deltaTime}, never a wall-clock (R5).
 */
class Mechanism1DofPlantTest {

    private static final double DT = 0.02; // 50 Hz sim tick

    /** An unloaded mechanism: gravity takes nothing away, so held power maps straight to speed. */
    private static MechanismSimConfig config(double timeConstant, double maxSpeed,
                                             double minPosition, double maxPosition) {
        return config(timeConstant, maxSpeed, minPosition, maxPosition, 0.0);
    }

    /** As above, but the mechanism's own weight costs {@code gravityHoldPowerFraction} of full effort. */
    private static MechanismSimConfig config(double timeConstant, double maxSpeed,
                                             double minPosition, double maxPosition,
                                             double gravityHoldPowerFraction) {
        return new MechanismSimConfig() {
            @Override public double timeConstant() { return timeConstant; }
            @Override public double maxSpeed() { return maxSpeed; }
            @Override public double minPosition() { return minPosition; }
            @Override public double maxPosition() { return maxPosition; }
            @Override public double gravityHoldPowerFraction() { return gravityHoldPowerFraction; }
        };
    }

    /** A FakeMotor whose only role here is to carry the power/direction the robot code sets. */
    private static FakeMotor motor() {
        return new FakeMotor(1000.0, 2.0);
    }

    // ==== INVARIANT-tier: Contract 1 — hard end-limits under adversarial commands ====

    @Test
    void fullPowerUpNeverPassesMaxLimit() {
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, config(0.1, 100.0, 0.0, 48.0));
        m.setPower(1.0); // slam up forever
        for (int i = 0; i < 500; i++) {
            plant.update(DT);
            assertTrue(plant.getPosition() <= 48.0,
                    "position must never integrate past maxPosition, saw " + plant.getPosition());
        }
        assertEquals(48.0, plant.getPosition(), 1e-6, "full power up must settle at maxPosition");
    }

    @Test
    void fullPowerDownNeverPassesMinLimit() {
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, config(0.1, 100.0, 0.0, 48.0));
        plant.setPosition(48.0); // start at the top
        m.setPower(-1.0);        // slam down forever
        for (int i = 0; i < 500; i++) {
            plant.update(DT);
            assertTrue(plant.getPosition() >= 0.0,
                    "position must never integrate past minPosition, saw " + plant.getPosition());
        }
        assertEquals(0.0, plant.getPosition(), 1e-6, "full power down must settle at minPosition");
    }

    @Test
    void reversedMotorDirectionStillRespectsLimits() {
        // A REVERSE motor flips the sign: +power drives DOWN. Pins both direction handling and that the
        // invariant holds regardless of how the season wired motor direction.
        FakeMotor m = motor();
        m.setDirection(DcMotorSimple.Direction.REVERSE);
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, config(0.1, 100.0, 0.0, 48.0));
        plant.setPosition(24.0);
        m.setPower(1.0); // reversed -> drives toward min
        for (int i = 0; i < 500; i++) {
            plant.update(DT);
            assertTrue(plant.getPosition() >= 0.0 && plant.getPosition() <= 48.0,
                    "reversed drive must stay within limits, saw " + plant.getPosition());
        }
        assertEquals(0.0, plant.getPosition(), 1e-6, "reversed +power must settle at minPosition");
    }

    @Test
    void singleHugeTickUpwardStillHoldsLimits() {
        // The adversarial step-size case (upward): one enormous deltaTime tick. A per-tick post-integration
        // clamp must hold no matter how large the step is.
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, config(0.5, 100.0, -20.0, 20.0));
        plant.setPosition(3.0);
        m.setPower(1.0);
        plant.update(1000.0); // absurdly large single tick
        assertTrue(plant.getPosition() <= 20.0 && plant.getPosition() >= -20.0,
                "huge single tick up must stay within limits, saw " + plant.getPosition());
    }

    @Test
    void singleHugeTickDownwardStillHoldsLimits() {
        // Mirror of the above toward minPosition — full Contract-1 coverage in both directions.
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, config(0.5, 100.0, -20.0, 20.0));
        plant.setPosition(3.0);
        m.setPower(-1.0);
        plant.update(1000.0);
        assertTrue(plant.getPosition() <= 20.0 && plant.getPosition() >= -20.0,
                "huge single tick down must stay within limits, saw " + plant.getPosition());
    }

    @Test
    void setPositionSeedIsClampedIntoLimits() {
        Mechanism1DofPlant plant = new Mechanism1DofPlant(motor(), config(0.1, 100.0, 0.0, 48.0));
        plant.setPosition(999.0);
        assertEquals(48.0, plant.getPosition(), 0.0, "seed above max must clamp");
        plant.setPosition(-999.0);
        assertEquals(0.0, plant.getPosition(), 0.0, "seed below min must clamp");
    }

    // ==== INVARIANT-tier: Contract 2 — first-order dynamics that converge (not kinematic-only) ====

    @Test
    void heldPowerVelocityConvergesToFirstOrderSteadyState() {
        // Under a held power the velocity must converge to power*maxSpeed (the first-order steady state),
        // and position must ride that velocity forward — dynamics, not an instant kinematic jump.
        double maxSpeed = 10.0;
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, config(0.3, maxSpeed, 0.0, 1e6));
        m.setPower(0.5); // steady state = 0.5 * 10 = 5 units/s
        for (int i = 0; i < 500; i++) plant.update(DT); // >> 5 tau
        assertEquals(5.0, plant.getVelocity(), 1e-6, "velocity must settle at power*maxSpeed");
        assertTrue(plant.getPosition() > 0.0, "position must integrate forward under sustained power");
    }

    @Test
    void oneTimeConstantVelocityReachesAbout63PercentOfSteadyState() {
        // The defining first-order signature: after one time constant the velocity is ~63% of steady
        // state. Wide band so ANY correct first-order discretization (exact-exp OR Euler) passes — pins
        // the contract (first-order dynamics), not the exact-exponential algorithm.
        double tau = 0.4;
        double maxSpeed = 10.0;
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, config(tau, maxSpeed, 0.0, 1e6));
        m.setPower(1.0); // steady state = 10 units/s

        int ticks = (int) Math.round(tau / DT); // one time constant
        for (int i = 0; i < ticks; i++) plant.update(DT);

        double fraction = plant.getVelocity() / maxSpeed;
        assertTrue(fraction > 0.55 && fraction < 0.72,
                "after one time constant velocity is ~63% of steady state (first-order lag), got "
                        + fraction);
    }

    @Test
    void firstTickIsNotAKinematicJump() {
        // One tick in, the mechanism has barely started moving (first-order lag) — nowhere near the
        // steady-state velocity a kinematic model would apply instantly.
        double maxSpeed = 10.0;
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, config(0.4, maxSpeed, 0.0, 1e6));
        m.setPower(1.0);
        plant.update(DT);
        assertTrue(plant.getVelocity() < 0.1 * maxSpeed,
                "after one tick velocity must be far below steady state (lag, not instant jump), got "
                        + plant.getVelocity());
    }

    // ==== INVARIANT-tier (R5): deterministic replay ====

    @Test
    void identicalPowerSequenceProducesIdenticalPosition() {
        FakeMotor ma = motor();
        FakeMotor mb = motor();
        Mechanism1DofPlant a = new Mechanism1DofPlant(ma, config(0.3, 20.0, 0.0, 48.0));
        Mechanism1DofPlant b = new Mechanism1DofPlant(mb, config(0.3, 20.0, 0.0, 48.0));
        double[] powers = {1.0, -0.5, 0.3, 0.0};
        for (double p : powers) {
            ma.setPower(p);
            mb.setPower(p);
            for (int i = 0; i < 50; i++) { a.update(DT); b.update(DT); }
        }
        assertEquals(a.getPosition(), b.getPosition(), 0.0,
                "same powers + same dt must yield bit-identical position (R5)");
    }

    // ==== INVARIANT-tier: glue-param / wiring validation (fail loud on unusable config) ====

    @Test
    void rejectsNullMotor() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanism1DofPlant(null, config(0.3, 10.0, 0.0, 48.0)));
    }

    @Test
    void rejectsNonPositiveTimeConstant() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanism1DofPlant(motor(), config(0.0, 10.0, 0.0, 48.0)));
    }

    @Test
    void rejectsNonPositiveMaxSpeed() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanism1DofPlant(motor(), config(0.3, 0.0, 0.0, 48.0)));
    }

    @Test
    void rejectsInvertedLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanism1DofPlant(motor(), config(0.3, 10.0, 48.0, 0.0)));
    }

    @Test
    void rejectsNonFiniteTimeConstant() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanism1DofPlant(motor(), config(Double.NaN, 10.0, 0.0, 48.0)));
    }

    @Test
    void rejectsInfiniteMaxSpeed() {
        // Infinity passes a naive "> 0" check but makes 0*Infinity = NaN on an idle tick — must be rejected.
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanism1DofPlant(motor(), config(0.3, Double.POSITIVE_INFINITY, 0.0, 48.0)));
    }

    @Test
    void rejectsNonFiniteLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanism1DofPlant(motor(), config(0.3, 10.0, 0.0, Double.NaN)));
    }

    @Test
    void nonFiniteSeedIsRejected() {
        Mechanism1DofPlant plant = new Mechanism1DofPlant(motor(), config(0.3, 10.0, 0.0, 48.0));
        assertThrows(IllegalArgumentException.class, () -> plant.setPosition(Double.NaN));
    }

    // ==== IMPLEMENTATION-tier: pins the current design's specific choices ====

    @Test
    void heldPowerVelocityMatchesAnalyticExponential() {
        // Pins the exact closed-form exp() update: from rest under a held power,
        // velocity(t) = steady*(1 - exp(-t/tau)) to fp precision. An Euler integrator would NOT match at
        // 1e-9 — this pins THIS algorithm, not the Contract.
        double tau = 0.5;
        double maxSpeed = 10.0;
        double steady = maxSpeed; // power = 1.0
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, config(tau, maxSpeed, 0.0, 1e6));
        m.setPower(1.0);

        double t = 0.0;
        for (int i = 0; i < 100; i++) {
            plant.update(DT);
            t += DT;
            double predicted = steady * (1.0 - StrictMath.exp(-t / tau));
            assertEquals(predicted, plant.getVelocity(), 1e-9,
                    "velocity must track the analytic first-order curve at t=" + t);
        }
    }

    @Test
    void hittingAWallStallsVelocityToZero() {
        // Pins the anti-windup choice — velocity resets to 0 while position sits at a hard stop still
        // driving into it, so a reversing command releases immediately instead of unwinding stored
        // velocity. A correct plant that clamps position but retains velocity still honors Contract 1.
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, config(0.1, 100.0, 0.0, 48.0));
        m.setPower(1.0);
        for (int i = 0; i < 500; i++) plant.update(DT); // drive into the top stop
        assertEquals(48.0, plant.getPosition(), 1e-6, "precondition: at the top limit");
        assertEquals(0.0, plant.getVelocity(), 0.0, "velocity must stall to 0 against the hard stop");
    }

    @Test
    void nonFiniteMotorPowerIsTreatedAsZeroForce() {
        // Pins the fail-safe policy (NaN power -> zero force). A different plant could fail loud instead
        // and still honor Contract 1; this asserts THIS design's choice, so it is implementation-tier.
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, config(0.3, 10.0, 0.0, 48.0));
        m.setPower(Double.NaN);
        for (int i = 0; i < 10; i++) plant.update(DT);
        assertEquals(0.0, plant.getPosition(), 0.0, "NaN power must be treated as zero force, not poison");
        assertEquals(0.0, plant.getVelocity(), 0.0, "NaN power must not corrupt velocity");
    }

    @Test
    void nonFiniteOrNonPositiveDeltaTimeIsANoOp() {
        // Pins the fail-safe policy (bad dt -> no time passes). A different plant could fail loud instead;
        // this asserts THIS design's choice, so it is implementation-tier. (Contract 1 holds either way.)
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, config(0.3, 10.0, 0.0, 48.0));
        plant.setPosition(12.0);
        m.setPower(1.0);
        plant.update(Double.NaN);
        plant.update(-0.02);
        plant.update(0.0);
        assertEquals(12.0, plant.getPosition(), 0.0, "bad ticks must not move (or corrupt) position");
    }

    // ==== INVARIANT-tier: gravity — a loaded mechanism sags at zero power and needs held effort ====

    /** A vertical carriage whose own weight costs a third of full effort to hold up. */
    private static MechanismSimConfig loadedConfig() {
        return config(0.1, 100.0, 0.0, 48.0, 1.0 / 3.0);
    }

    @Test
    void aLoadedMechanismFallsAtZeroPower() {
        // The whole reason gravity is modelled: without it, "hold this height" is a test that passes for a
        // controller that outputs nothing at all.
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, loadedConfig());
        plant.setPosition(24.0);
        m.setPower(0.0);
        for (int i = 0; i < 500; i++) plant.update(DT);
        assertEquals(0.0, plant.getPosition(), 1e-6,
                "a mechanism gravity loads must fall to its lower limit when nothing holds it");
    }

    @Test
    void holdingPowerEqualToTheLoadHoldsPositionExactly() {
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, loadedConfig());
        plant.setPosition(24.0);
        m.setPower(1.0 / 3.0);   // exactly the weight
        for (int i = 0; i < 500; i++) plant.update(DT);
        assertEquals(24.0, plant.getPosition(), 1e-6,
                "power exactly equal to the load must neither raise nor drop the mechanism");
    }

    @Test
    void gravityIsSubtractedFromTheCommandedRate() {
        // Steady-state velocity is (power - gravity) * maxSpeed, not power * maxSpeed. Same command, two
        // configs: the loaded one must run slower by exactly the weight's share of max speed.
        FakeMotor unloadedMotor = motor();
        Mechanism1DofPlant unloaded =
                new Mechanism1DofPlant(unloadedMotor, config(0.1, 100.0, -1000.0, 1000.0));
        FakeMotor loadedMotor = motor();
        Mechanism1DofPlant loaded =
                new Mechanism1DofPlant(loadedMotor, config(0.1, 100.0, -1000.0, 1000.0, 1.0 / 3.0));

        unloadedMotor.setPower(1.0);
        loadedMotor.setPower(1.0);
        for (int i = 0; i < 200; i++) {
            unloaded.update(DT);
            loaded.update(DT);
        }
        assertEquals(100.0, unloaded.getVelocity(), 1e-3);
        assertEquals(100.0 * (1.0 - 1.0 / 3.0), loaded.getVelocity(), 1e-3,
                "the load must cost exactly its fraction of max speed");
    }

    @Test
    void aMechanismGravityCannotHoldStillSinksEvenAtFullPower() {
        // gravityHoldPowerFraction is capped below 1.0 by the constructor precisely so this cannot be a
        // mechanism that full effort fails to hold; check the boundary is where it is claimed to be.
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanism1DofPlant(motor(), config(0.1, 100.0, 0.0, 48.0, 1.0)),
                "a load full power cannot hold is a configuration mistake, not a mechanism");
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanism1DofPlant(motor(), config(0.1, 100.0, 0.0, 48.0, -0.1)),
                "a negative load would mean gravity HELPS the mechanism climb");
    }

    @Test
    void aStalledLoadedMechanismReportsZeroShaftSpeed() {
        // The seam a FakeMotor's current model reads: pinned against a limit, the shaft is stopped, so the
        // fraction is exactly 0 and the motor can report stall current.
        FakeMotor m = motor();
        Mechanism1DofPlant plant = new Mechanism1DofPlant(m, loadedConfig());
        m.setPower(1.0);
        for (int i = 0; i < 500; i++) plant.update(DT);
        assertEquals(48.0, plant.getPosition(), 1e-6, "full power must drive it up to the limit");
        assertEquals(0.0, plant.getVelocityFractionOfMaxSpeed(), 0.0,
                "sitting on a limit still driving into it, the shaft speed fraction must be exactly zero");
    }

    @Test
    void shaftSpeedFractionIsVelocityOverMaxSpeed() {
        FakeMotor m = motor();
        Mechanism1DofPlant plant =
                new Mechanism1DofPlant(m, config(0.1, 100.0, -1000.0, 1000.0, 1.0 / 3.0));
        m.setPower(1.0);
        for (int i = 0; i < 200; i++) plant.update(DT);
        assertEquals(plant.getVelocity() / 100.0, plant.getVelocityFractionOfMaxSpeed(), 0.0);
        assertEquals(1.0 - 1.0 / 3.0, plant.getVelocityFractionOfMaxSpeed(), 1e-5);
    }
}
