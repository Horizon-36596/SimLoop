package org.horizon36596.simloop.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.horizon36596.simloop.config.DrivetrainSimConfig;
import org.horizon36596.simloop.config.MechanismSimConfig;
import org.horizon36596.simloop.config.SimRobotConfig;
import org.horizon36596.simloop.fakehardware.FakeHardwareMap;
import org.horizon36596.simloop.fakehardware.FakeMotor;
import org.horizon36596.simloop.loop.RunResult;
import org.horizon36596.simloop.plant.Mechanism1DofPlant;
import org.horizon36596.simloop.sim.FakeTimer;
import org.horizon36596.simloop.sim.ScenarioRunner;

import org.junit.jupiter.api.Test;
import org.psilynx.psikit.core.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The worked example printed on the documentation site's "Your first simulated test" page.
 *
 * <p><b>This file exists so that page cannot be wrong.</b> The code on the page is this file, and this
 * file runs in CI with every other test - so a rename, a changed signature or a changed default breaks
 * the build rather than quietly turning the example into something that no longer compiles. If you edit
 * one, edit the other: {@code SimLoop/docs/first-test.md}.
 *
 * <p>It is deliberately the smallest thing that still touches the whole path a team actually walks:
 * a fake motor, a plant behind it, a controller written the way robot code writes one, a scenario that
 * logs, and a scored result read back out of the RLOG.
 */
class SlideReachesTargetExampleTest {

    /** Fixed sim time step, in seconds. 0.02 s is 50 Hz, the rate an FTC OpMode loop is written for. */
    private static final double DELTA_TIME_SECONDS = 0.02;

    /** How long the scenario runs, in ticks. 150 ticks at 0.02 s is three seconds of robot time. */
    private static final int TICKS = 150;

    /** Where the slide is told to go, in inches. */
    private static final double TARGET_INCHES = 12.0;

    /**
     * The log key the slide's position is READ BACK under, which is not the key it was written under.
     * PsiKit files everything {@code Logger.recordOutput} writes beneath {@code RealOutputs/}, so a run
     * that logged {@code "Slide/positionInches"} is queried as {@code "RealOutputs/Slide/positionInches"}.
     * Getting this wrong is the most common first-run surprise; it throws
     * {@code RunResult.MetricNotFoundException} rather than returning zero, on purpose.
     */
    private static final String SLIDE_POSITION = "RealOutputs/Slide/positionInches";

    /**
     * The slide's physical numbers. On a real robot these are measured; here they are plausible values
     * for a two-stage linear slide on a 435 rpm motor.
     */
    private static final MechanismSimConfig SLIDE = new MechanismSimConfig() {
        @Override public double timeConstant() { return 0.12; }          // seconds
        @Override public double maxSpeed() { return 30.0; }              // inches per second
        @Override public double minPosition() { return 0.0; }            // inches
        @Override public double maxPosition() { return 24.0; }           // inches
        @Override public double gravityHoldPowerFraction() { return 0.0; } // unitless; a horizontal slide
    };

    /**
     * SimLoop needs a robot-level config even when the scenario drives no drivetrain, because a run is
     * named after the robot it ran on. A season repo implements this once and reuses it.
     */
    private static final SimRobotConfig ROBOT = new SimRobotConfig() {
        @Override public String[] driveMotorNames() { return new String[] {"fl", "fr", "bl", "br"}; }
        @Override public String odometryName() { return "odo"; }
        @Override public DrivetrainSimConfig drivetrain() {
            return new DrivetrainSimConfig() {
                @Override public double trackWidth() { return 12.0; }                  // inches
                @Override public double wheelBase() { return 12.0; }                   // inches
                @Override public double wheelRadius() { return 2.0; }                  // inches
                @Override public double ticksPerInch() { return 100.0; }               // ticks per inch
                @Override public double maxVelocityTicksPerSecond() { return 2000.0; } // ticks per second
                @Override public double maxAccel() { return 8.0; }                     // 1/seconds
                @Override public double fieldHalfWidth() { return 72.0; }              // inches
                @Override public double fieldHalfHeight() { return 72.0; }             // inches
            };
        }
    };

    @Test
    void theSlideReachesItsTargetAndStaysThere() throws IOException {
        // 1. The clock. Every sim-side thing that asks the time asks this, never the wall (domain R5).
        FakeTimer timer = new FakeTimer();

        // 2. The hardware your robot code will resolve, under the names your robot config uses.
        FakeMotor slideMotor = new FakeMotor(2000.0, 8.0);
        FakeHardwareMap hardwareMap = new FakeHardwareMap();
        hardwareMap.register("slide", slideMotor);

        // 3. The physics behind that motor. The plant reads the motor's commanded power and moves.
        Mechanism1DofPlant slidePlant = new Mechanism1DofPlant(slideMotor, SLIDE);

        // 4. Somewhere to put the log. build/ is gitignored, which is where a run belongs.
        Path rlogPath = Paths.get("build", "sim", "slide-reaches-target.rlog");

        ScenarioRunner.run("SlideExample", ROBOT, timer, TICKS, DELTA_TIME_SECONDS, rlogPath, deltaTime -> {
            // This is the part that, on a real robot, is your subsystem's periodic(). A proportional
            // controller is enough to show the shape; yours will be whatever you actually run.
            double error = TARGET_INCHES - slidePlant.getPosition();
            double power = Math.max(-1.0, Math.min(1.0, error * 0.25));
            slideMotor.setPower(power);

            // The plant advances on the same tick the robot code just commanded.
            slidePlant.update(deltaTime);

            // Tell the motor how fast its shaft is REALLY turning. Without this line the motor keeps
            // modelling a happily spinning shaft even while the slide sits on an end stop, so anything
            // that reads motor current - a stall detector, a re-zero routine - reads a number the
            // mechanism does not agree with. A drivetrain motor never needs it; a mechanism motor always
            // does.
            slideMotor.setMeasuredShaftSpeedFraction(slidePlant.getVelocityFractionOfMaxSpeed());

            hardwareMap.updateAll(deltaTime);

            // Log what you want to assert on, and what you want to look at in AdvantageScope.
            Logger.recordOutput("Slide/positionInches", slidePlant.getPosition());
            Logger.recordOutput("Slide/commandedPower", power);
        });

        // 5. Read the run back out of the log and score it. Nothing here looks at the plant object -
        //    the log is the record, which is what makes the same assertions work on a real robot's log.
        RunResult run = RunResult.fromRlog("SlideExample", rlogPath, TICKS);

        assertEquals(TARGET_INCHES, run.finalValue(SLIDE_POSITION), 0.25,
                "the slide should finish within a quarter inch of its target");

        // Overshoot, not the end stop. Asserting the slide stayed inside minPosition..maxPosition would
        // pass no matter what the controller did, because the plant clamps to those every tick - it is
        // the model's guarantee, not the controller's achievement. Overshoot is the thing this gain can
        // actually get wrong: this run peaks around 13 inches, so 2 inches of headroom catches a change
        // that makes it ring without failing on ordinary tuning noise.
        assertTrue(run.maxValue(SLIDE_POSITION) <= TARGET_INCHES + 2.0,
                "the slide overshot its target by more than two inches: "
                        + run.maxValue(SLIDE_POSITION));
    }
}
