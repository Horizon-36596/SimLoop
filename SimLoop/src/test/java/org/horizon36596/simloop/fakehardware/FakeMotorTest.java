package org.horizon36596.simloop.fakehardware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qualcomm.robotcore.hardware.DcMotorSimple;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.junit.jupiter.api.Test;

/** Batch 1.4: FakeMotor first-order model behaviour (fakehardware-plant §3). */
class FakeMotorTest {

    private static final double DT = 0.02;

    @Test
    void setPowerClampsToUnitRange() {
        FakeMotor motor = new FakeMotor(1000.0, 2.0);
        motor.setPower(5.0);
        assertEquals(1.0, motor.getPower());
        motor.setPower(-5.0);
        assertEquals(-1.0, motor.getPower());
    }

    @Test
    void speedRampsTowardCommandedPowerWithFirstOrderLag() {
        FakeMotor motor = new FakeMotor(1000.0, 2.0);
        motor.setPower(1.0);

        double previous = motor.getSpeed();
        for (int i = 0; i < 5; i++) {
            motor.update(DT);
            assertTrue(motor.getSpeed() > previous, "speed should increase toward commanded power");
            previous = motor.getSpeed();
        }
        // After enough ticks it approaches (but never exceeds) the commanded power.
        for (int i = 0; i < 500; i++) motor.update(DT);
        assertTrue(motor.getSpeed() > 0.99 && motor.getSpeed() <= 1.0);
    }

    @Test
    void positionIntegratesWhileMoving() {
        FakeMotor motor = new FakeMotor(1000.0, 2.0);
        motor.setPower(1.0);
        for (int i = 0; i < 100; i++) motor.update(DT);
        assertTrue(motor.getCurrentPosition() > 0, "encoder position should advance under positive power");
    }

    @Test
    void reverseDirectionReportsPositiveWhilePhysicalShaftTurnsTheOtherWay() {
        // BACKLOG B18 / two-accessor split: a real DcMotorEx set to REVERSE re-applies direction on READ,
        // so software always sees "positive power -> positive reported position" no matter which way the
        // motor is wired. The SDK-reported pair must reflect that; the physical pair must not.
        FakeMotor motor = new FakeMotor(1000.0, 2.0);
        motor.setDirection(DcMotorSimple.Direction.REVERSE);
        motor.setPower(1.0);
        for (int i = 0; i < 100; i++) motor.update(DT);
        assertTrue(motor.getCurrentPosition() > 0,
                "REVERSE + positive power must report positive position, matching real DcMotorEx");
        assertTrue(motor.getVelocityTicksPerSecond() > 0,
                "REVERSE + positive power must report positive velocity, matching real DcMotorEx");
        assertTrue(motor.getPhysicalPositionTicks() < 0,
                "the physical shaft must actually have turned the other way");
        assertTrue(motor.getPhysicalVelocityTicksPerSecond() < 0,
                "the physical shaft velocity must actually be negative");
    }

    @Test
    void speedDecaysToExactlyZeroWhenPowerRemoved() {
        FakeMotor motor = new FakeMotor(1000.0, 2.0);
        motor.setPower(1.0);
        for (int i = 0; i < 200; i++) motor.update(DT);
        motor.setPower(0.0);
        for (int i = 0; i < 500; i++) motor.update(DT);
        assertEquals(0.0, motor.getSpeed(), "speed must snap to exactly 0 (deterministic rest state)");
    }

    // ==== Current draw. Robot code detects a hardstop or a jam by reading amps, so this must be a real
    //      back-EMF model and not a stub. Area S (slide re-zero + stall detection) is its first consumer. ====

    /** A 5203 as the harness builds one: datasheet stall 9.2 A, no-load 0.25 A. */
    private static FakeMotor motorWithDatasheetCurrents() {
        return new FakeMotor(1000.0, 2.0, 537.7, 9.2, 0.25);
    }

    @Test
    void anIdleMotorDrawsNoCurrent() {
        FakeMotor motor = motorWithDatasheetCurrents();
        assertEquals(0.0, motor.getCurrent(CurrentUnit.AMPS), 1e-9,
                "a motor commanded nothing and turning nothing draws nothing");
    }

    @Test
    void aStalledMotorDrawsFarMoreThanAFreeSpinningOne() {
        FakeMotor spinning = motorWithDatasheetCurrents();
        spinning.setPower(1.0);
        for (int i = 0; i < 500; i++) spinning.update(DT);   // let it reach free speed
        double freeAmps = spinning.getCurrent(CurrentUnit.AMPS);

        FakeMotor stalled = motorWithDatasheetCurrents();
        stalled.setPower(1.0);
        stalled.setMeasuredShaftSpeedFraction(0.0);          // something is holding the shaft
        double stalledAmps = stalled.getCurrent(CurrentUnit.AMPS);

        assertTrue(freeAmps < 0.5, "a motor at free speed draws about its no-load current, got " + freeAmps);
        assertEquals(9.2, stalledAmps, 1e-9, "a motor held at full power must draw its stall current");
        assertTrue(stalledAmps > 10 * freeAmps, "the stall/free ratio is the whole detection signal");
    }

    @Test
    void currentBetweenTheExtremesIsTheSumOfTheTorqueAndFrictionTerms() {
        // The two tests above only pin the endpoints, where a sign error in either term is invisible: at
        // full stall the friction term is zero, and at free speed the torque term is. Pin one interior
        // point by hand so "+ friction" cannot silently become "- friction".
        //   I = stall * |power - shaftFraction| + free * |shaftFraction|
        //     = 9.2 * |0.60 - 0.25|            + 0.25 * 0.25
        //     = 9.2 * 0.35 + 0.0625            = 3.2825 A
        FakeMotor motor = motorWithDatasheetCurrents();
        motor.setPower(0.60);
        motor.setMeasuredShaftSpeedFraction(0.25);
        assertEquals(3.2825, motor.getCurrent(CurrentUnit.AMPS), 1e-9,
                "a motor pushing harder than the shaft is turning draws the torque term plus friction");
    }

    @Test
    void currentNeverExceedsTheStallCeiling() {
        // The back-EMF term alone can exceed stall when a motor is driven against its own motion (plugging).
        // A real motor's winding resistance sets a hard ceiling; so does this one.
        FakeMotor motor = motorWithDatasheetCurrents();
        motor.setPower(1.0);
        motor.setMeasuredShaftSpeedFraction(-1.0);           // driven forward while spinning backward
        assertEquals(9.2, motor.getCurrent(CurrentUnit.AMPS), 1e-9,
                "reported current must saturate at the datasheet stall current");
    }

    @Test
    void currentIsReportedInWhicheverUnitIsAsked() {
        FakeMotor motor = motorWithDatasheetCurrents();
        motor.setPower(1.0);
        motor.setMeasuredShaftSpeedFraction(0.0);
        assertEquals(9200.0, motor.getCurrent(CurrentUnit.MILLIAMPS), 1e-6);
    }

    @Test
    void overCurrentTripsOnlyOnceAnAlertIsSet() {
        FakeMotor motor = motorWithDatasheetCurrents();
        motor.setPower(1.0);
        motor.setMeasuredShaftSpeedFraction(0.0);            // drawing the full 9.2 A
        assertTrue(!motor.isOverCurrent(), "with no alert configured a motor is never over-current");

        motor.setCurrentAlert(5.0, CurrentUnit.AMPS);
        assertEquals(5.0, motor.getCurrentAlert(CurrentUnit.AMPS), 1e-9);
        assertTrue(motor.isOverCurrent(), "9.2 A against a 5 A alert must trip");
    }

    // ==== The shaft-speed override: what makes the current above mean anything for a real mechanism. ====

    @Test
    void anInjectedShaftSpeedOverridesTheMotorsOwnModelEverywhere() {
        FakeMotor motor = motorWithDatasheetCurrents();
        motor.setPower(1.0);
        for (int i = 0; i < 500; i++) motor.update(DT);      // its own model says "spinning at free speed"

        double freeRunningPosition = motor.getPhysicalPositionTicks();
        motor.setMeasuredShaftSpeedFraction(0.0);            // ...but the mechanism is jammed

        assertEquals(0.0, motor.getSpeed(), 1e-9, "the injected truth wins over the motor's own model");
        assertEquals(0.0, motor.getVelocityTicksPerSecond(), 1e-9);
        assertEquals(0.0, motor.getPhysicalVelocityTicksPerSecond(), 1e-9);

        // And the encoder must stop counting: a real motor encoder counts the shaft, not the command.
        for (int i = 0; i < 50; i++) motor.update(DT);
        assertEquals(freeRunningPosition, motor.getPhysicalPositionTicks(), 1e-9,
                "a held shaft must not keep accumulating encoder ticks");
    }

    @Test
    void clearingTheOverrideHandsAuthorityBackToTheMotorsOwnModel() {
        FakeMotor motor = motorWithDatasheetCurrents();
        motor.setPower(1.0);
        for (int i = 0; i < 500; i++) motor.update(DT);
        motor.setMeasuredShaftSpeedFraction(0.0);
        assertEquals(0.0, motor.getSpeed(), 1e-9);

        motor.clearMeasuredShaftSpeedFraction();
        assertTrue(motor.getSpeed() > 0.99, "clearing the override restores the motor's own modelled speed");
    }

    @Test
    void anInjectedShaftSpeedIsClampedAndMustBeFinite() {
        FakeMotor motor = motorWithDatasheetCurrents();
        motor.setMeasuredShaftSpeedFraction(5.0);
        assertEquals(1.0, motor.getSpeed(), 1e-9, "a fraction of free speed cannot exceed 1");
        motor.setMeasuredShaftSpeedFraction(-5.0);
        assertEquals(-1.0, motor.getSpeed(), 1e-9);

        assertThrows(IllegalArgumentException.class, () -> motor.setMeasuredShaftSpeedFraction(Double.NaN),
                "a non-finite shaft speed is a caller bug, not a value to fake-correct");
    }

    @Test
    void badCurrentRatingsAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new FakeMotor(1000.0, 2.0, 537.7, 0.0, 0.0),
                "a motor with no stall current has no current model at all");
        assertThrows(IllegalArgumentException.class, () -> new FakeMotor(1000.0, 2.0, 537.7, 9.2, 12.0),
                "no-load current above stall current is not a motor");
    }

    // ---------------------------------------------------------------------------------------------
    // An encoder plugged into this port that counts some OTHER mechanism (Q2-core)
    // ---------------------------------------------------------------------------------------------

    /**
     * Steps the motor for a while. One 20 ms tick off a standstill advances this test motor less than a
     * single encoder tick, so {@code getCurrentPosition()} would still read 0 and a test asserting the
     * shaft moved would fail for arithmetic reasons rather than real ones.
     */
    private static void stepFor(FakeMotor motor, int ticks) {
        for (int i = 0; i < ticks; i++) {
            motor.update(DT);
        }
    }

    /**
     * A port whose encoder counts a different mechanism reports that mechanism, not this motor's shaft.
     * Position and velocity both, because a real port derives both from the one encoder channel plugged
     * into it.
     */
    @Test
    void anotherMechanismsEncoderIsWhatThePortReports() {
        FakeMotor motor = new FakeMotor(1000.0, 2.0);
        motor.setPower(1.0);
        stepFor(motor, 25);
        assertTrue(motor.getCurrentPosition() != 0, "precondition: the motor's own shaft did move");

        motor.setEncoderMeasuringAnotherMechanism(1234.0, 250.0);

        assertTrue(motor.hasEncoderMeasuringAnotherMechanism());
        assertEquals(1234, motor.getCurrentPosition(),
                "the port must report the encoder plugged into it, not this motor's shaft");
        assertEquals(250.0, motor.getVelocityTicksPerSecond(),
                "velocity comes off the same encoder channel as position");
    }

    /**
     * The other mechanism's rate is a real ticks/second number, not a fraction of anything. Scaling it by
     * this motor's free speed would be the easy mistake, and it would silently rescale every velocity a
     * control loop reads off a borrowed port.
     */
    @Test
    void theOtherMechanismsVelocityIsNotScaledByThisMotorsFreeSpeed() {
        FakeMotor motor = new FakeMotor(1000.0, 2.0, 1000.0);
        motor.setEncoderMeasuringAnotherMechanism(0.0, 5000.0);

        assertEquals(5000.0, motor.getVelocityTicksPerSecond(),
                "5000 ticks/s is 5000 ticks/s even on a motor whose own free speed is 1000");
        assertEquals(5000.0, motor.getVelocity(), "getVelocity() is built on the same number");
        assertEquals(10.0 * Math.PI, motor.getVelocity(AngleUnit.RADIANS), 1e-9,
                "and so is the angular form: 5000 ticks/s over 1000 ticks/rev is 5 rev/s");
    }

    /**
     * Direction is applied by the handle, not by the encoder, so a REVERSE handle on this port negates the
     * other mechanism's numbers exactly as it negates the motor's own (BACKLOG B18).
     */
    @Test
    void directionIsStillAppliedOnReadToAnotherMechanismsEncoder() {
        FakeMotor motor = new FakeMotor(1000.0, 2.0);
        motor.setDirection(DcMotorSimple.Direction.REVERSE);
        motor.setEncoderMeasuringAnotherMechanism(1234.0, 250.0);

        assertEquals(-1234, motor.getCurrentPosition(),
                "a REVERSE handle reports the negated count, borrowed port or not");
        assertEquals(-250.0, motor.getVelocityTicksPerSecond(),
                "and the negated rate with it");
    }

    /**
     * Declaring the port's encoder replaces what the port REPORTS and nothing else. The motor whose port was
     * borrowed is still a motor: it keeps integrating its own power, keeps its own physical position and
     * velocity, and keeps modelling its own current. On the robot that motor really does keep spinning
     * while the number its port reports belongs to somebody else.
     */
    @Test
    void anotherMechanismsEncoderChangesNothingButWhatThePortReports() {
        FakeMotor motor = new FakeMotor(1000.0, 2.0);
        motor.setPower(1.0);
        stepFor(motor, 25);
        motor.setEncoderMeasuringAnotherMechanism(1234.0, -5000.0);

        double physicalPositionBefore = motor.getPhysicalPositionTicks();
        double physicalVelocityBefore = motor.getPhysicalVelocityTicksPerSecond();
        double currentBefore = motor.getCurrent(CurrentUnit.AMPS);
        motor.update(DT);

        assertEquals(1234, motor.getCurrentPosition(), "the declared values are used until they are replaced");
        assertEquals(-5000.0, motor.getVelocityTicksPerSecond());
        assertTrue(motor.getPhysicalPositionTicks() > physicalPositionBefore,
                "the motor must go on integrating its own shaft while its port reports something else");
        assertTrue(motor.getPhysicalVelocityTicksPerSecond() >= physicalVelocityBefore
                        && motor.getPhysicalVelocityTicksPerSecond() > 0.0,
                "the physical pair still describes this motor's own shaft, spinning forwards");
        assertTrue(motor.getCurrent(CurrentUnit.AMPS) > 0.0 && currentBefore > 0.0,
                "the current model is untouched — current belongs to this motor's own winding");
    }

    /**
     * Clearing hands the port back to this motor's own encoder count — the count it kept the whole time,
     * not one frozen at the moment the other mechanism took the readout over. The motor is stepped while
     * the port is reporting elsewhere, so an implementation that paused its own integration would fail here.
     */
    @Test
    void clearingGivesBackThisMotorsOwnCountWhichNeverStoppedIntegrating() {
        FakeMotor motor = new FakeMotor(1000.0, 2.0);
        motor.setPower(1.0);
        stepFor(motor, 25);
        int ownCountBefore = motor.getCurrentPosition();
        assertTrue(ownCountBefore != 0, "precondition: the motor counted something of its own");

        motor.setEncoderMeasuringAnotherMechanism(9999.0, 0.0);
        assertEquals(9999, motor.getCurrentPosition());
        stepFor(motor, 25);
        assertEquals(9999, motor.getCurrentPosition(), "still reporting the other mechanism while it runs");

        motor.clearEncoderMeasuringAnotherMechanism();

        assertTrue(motor.getCurrentPosition() > ownCountBefore,
                "the motor's own count never stopped, so it comes back ahead of where it was");
        assertEquals((int) motor.getPhysicalPositionTicks(), motor.getCurrentPosition(),
                "and it is exactly this motor's own shaft again");
        assertTrue(motor.getVelocityTicksPerSecond() > 0.0, "velocity comes back with it");
        assertFalse(motor.hasEncoderMeasuringAnotherMechanism());
    }

    @Test
    void aNonFiniteEncoderReadingIsRejected() {
        FakeMotor motor = new FakeMotor(1000.0, 2.0);
        assertThrows(IllegalArgumentException.class,
                () -> motor.setEncoderMeasuringAnotherMechanism(Double.NaN, 0.0),
                "a non-finite count would turn every position read on this port into nonsense");
        assertThrows(IllegalArgumentException.class,
                () -> motor.setEncoderMeasuringAnotherMechanism(Double.POSITIVE_INFINITY, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> motor.setEncoderMeasuringAnotherMechanism(0.0, Double.NaN),
                "and the same for the rate");
        assertThrows(IllegalArgumentException.class,
                () -> motor.setEncoderMeasuringAnotherMechanism(0.0, Double.NEGATIVE_INFINITY));
    }

    /**
     * A fresh motor reports its own shaft. Declaring another mechanism's encoder is opt-in, so every
     * drivetrain motor and every existing caller is unaffected.
     */
    @Test
    void afreshMotorReportsItsOwnShaft() {
        FakeMotor motor = new FakeMotor(1000.0, 2.0);
        assertFalse(motor.hasEncoderMeasuringAnotherMechanism());
        motor.setPower(1.0);
        stepFor(motor, 25);
        assertEquals((int) motor.getPhysicalPositionTicks(), motor.getCurrentPosition());
        assertEquals(motor.getPhysicalVelocityTicksPerSecond(), motor.getVelocityTicksPerSecond());
    }
}
