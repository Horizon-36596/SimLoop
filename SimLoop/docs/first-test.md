# Your first simulated test

By the end of this page you have a test that drives a linear slide to a target in simulation, asserts it
got there, and leaves an RLOG you can open in AdvantageScope.

!!! success "This example is not a sketch"
    The code below is a real test in SimLoop's own test sourceset -
    `SimLoop/src/test/java/org/horizon36596/simloop/example/SlideReachesTargetExampleTest.java` - and it
    runs in CI with everything else. If a signature on this page ever stops compiling, the build goes red
    before you find out the hard way.

## The five things a simulated test always has

Whatever you are testing, the shape is the same:

1. **A clock.** `FakeTimer`. Everything sim-side that asks the time asks this one, never the wall. That is
   what makes two runs of the same scenario produce the same log.
2. **Fake hardware, under your real config names.** Register each fake in a `FakeHardwareMap` using the
   same string your robot configuration uses, and your robot code resolves it without knowing.
3. **A plant behind each fake.** The fake is the interface; the plant is the physics. A `FakeMotor` on its
   own reports whatever it was told; a `Mechanism1DofPlant` behind it makes the slide actually move.
4. **A scenario.** `ScenarioRunner.run` owns the fixed-timestep loop and the whole logging lifecycle, so
   your test does not hand-write either.
5. **Assertions against the log, not the objects.** Read the run back with `RunResult`. The same
   assertions then work against a log a real robot produced, which is the entire point.

## The test

```java
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
```

## Two things that will catch you

**The key you log is not the key you read.** PsiKit files everything `Logger.recordOutput` writes beneath
`RealOutputs/`. A run that logged `"Slide/positionInches"` is queried as
`"RealOutputs/Slide/positionInches"`. Get it wrong and `RunResult` throws `MetricNotFoundException` rather
than handing you a zero — a silent zero would be a passing test that measured nothing.

**A mechanism motor has to be told how fast it is really turning.** `FakeMotor` models its own unloaded
speed and knows nothing about what is bolted to it, so a slide parked on its end stop would still report a
spinning shaft and near-zero current — and current is what a stall detector and a re-zero routine read.
The `setMeasuredShaftSpeedFraction` line above is what fixes that, and it is needed every tick. A
drivetrain motor never calls it.

**Update the plant on the same tick the robot code commanded it.** The order inside the scenario body is:
read state, decide, command the fake, then `plant.update(deltaTime)`. Commanding after updating shifts
every response one tick late, which looks like a tuning problem and is not.

!!! note "Why there is no `assertTrue(run.isComplete())`"
    `ScenarioRunner.run` already throws if the log does not decode to one frame per tick — that is one of
    the four things it refuses to hand back a log for. Asserting it afterwards would always pass, which is
    worse than not asserting it: it reads like a check and is not one.

## Running it

Put the test in **your own** test sourceset — `TeamCode/src/test/java/...` in a standard FTC project —
and run your module's test task:

```powershell
./gradlew :TeamCode:testDebugUnitTest
```

Three seconds of robot time takes well under a second of yours.

(The copy that lives in SimLoop's own tests is not something you need; it is there so that this page
cannot drift away from the library.)

## Opening the log

The run wrote `build/sim/slide-reaches-target.rlog`. Open it in
[AdvantageScope](https://github.com/Mechanical-Advantage/AdvantageScope): **File - Open**, pick the file,
then drag `RealOutputs/Slide/positionInches` onto a line graph. You should see it rise and flatten at 12
inches.

!!! warning "`build/` is deleted by `./gradlew clean`"
    The log is a build output, not a document. If it is gone, re-run the test - that is the command that
    regenerates it.

## Where to go next

- [What it does not do](limits.md) - read this before you plan anything around SimLoop.
- [The `plant` package](packages/plant.md) - what the model behind your mechanism actually is, and what
  it is not.
- [Troubleshooting](troubleshooting.md) - the failures that look like a bug in your robot code and are not.
