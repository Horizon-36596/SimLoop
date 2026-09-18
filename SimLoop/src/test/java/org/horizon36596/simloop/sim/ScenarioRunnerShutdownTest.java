package org.horizon36596.simloop.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.horizon36596.simloop.config.DrivetrainSimConfig;
import org.horizon36596.simloop.config.SimRobotConfig;

import org.junit.jupiter.api.Test;
import org.psilynx.psikit.core.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Pins that {@link ScenarioRunner#run} shuts PsiKit down when a run fails, not only when it succeeds.
 *
 * <p>PsiKit's {@code Logger} is global static state holding the RLOG writer's open file handle. Until
 * 2026-09-17 the {@code Logger.end()} call sat after the tick loop with nothing guarding it, so a throw out
 * of the scenario body walked straight past it and left the writer running. Found by that day's adversarial
 * review (finding logger-not-ended-on-failure).
 */
class ScenarioRunnerShutdownTest {

    private static final SimRobotConfig STUB_CONFIG = new SimRobotConfig() {
        @Override public String[] driveMotorNames() { return new String[] {"fl", "fr", "bl", "br"}; }
        @Override public String odometryName() { return "odo"; }
        @Override public DrivetrainSimConfig drivetrain() {
            return new DrivetrainSimConfig() {
                @Override public double trackWidth() { return 12.0; }
                @Override public double wheelBase() { return 12.0; }
                @Override public double wheelRadius() { return 2.0; }
                @Override public double ticksPerInch() { return 100.0; }
                @Override public double maxVelocityTicksPerSecond() { return 1000.0; }
                @Override public double maxAccel() { return 2.0; }
                @Override public double fieldHalfWidth() { return 72.0; }
                @Override public double fieldHalfHeight() { return 72.0; }
            };
        }
    };

    /**
     * A failure the scenario body raises, named so the test can tell it apart from a real bug in the runner.
     */
    private static final class TheScenarioBodyFailed extends RuntimeException {
        TheScenarioBodyFailed() {
            super("deliberate failure from the scenario body, on tick 2");
        }
    }

    @Test
    void aThrowFromTheScenarioBodyStillShutsTheLoggerDown() throws IOException {
        Path rlog = Paths.get("build", "sim", "shutdown-body-throws.rlog");
        Files.deleteIfExists(rlog);
        FakeTimer timer = new FakeTimer();

        TheScenarioBodyFailed thrown = assertThrows(TheScenarioBodyFailed.class,
                () -> ScenarioRunner.run("BodyThrows", STUB_CONFIG, timer,
                        100, 0.02, rlog, deltaTime -> {
                            Logger.recordOutput("Test/field0", timer.time());
                            if (timer.time() > 0.03) {
                                throw new TheScenarioBodyFailed();
                            }
                        }));

        // PsiKit exposes no "is the logger still running?" read, so the check is the observable consequence:
        // a writer that was ended has flushed and closed its file, which means what it did write decodes.
        // A writer left running mid-run has not.
        assertTrue(Files.exists(rlog) && Files.size(rlog) > 0,
                "the partial log was flushed to disk rather than left open in the writer");
        assertEquals(1, RlogDecodedCompare.decode(rlog).size(),
                "the one tick that completed before the failure is readable, which it only is if the "
                        + "writer was ended");

        // The shutdown must not have eaten the reason the run failed. Asserted in the SAME test as the flush
        // above on purpose: on its own this assertion passes against the pre-fix code too, because back then
        // the exception propagated with nothing in its way. It only pins anything once it sits beside proof
        // that a shutdown did run.
        assertEquals("deliberate failure from the scenario body, on tick 2", thrown.getMessage(),
                "the original failure is what the caller sees, not a shutdown error");
        // The other half of that contract -- a shutdown failure arriving as addSuppressed rather than
        // replacing the original -- has no test, and cannot have one from here: reaching it needs
        // Logger.end() itself to throw, and PsiKit offers no seam to make it. Reading the catch block in
        // ScenarioRunner is the only check that branch gets.
    }

    @Test
    void aRunThatFailsDoesNotPoisonTheNextOne() throws IOException {
        FakeTimer failingTimer = new FakeTimer();
        Path failed = Paths.get("build", "sim", "shutdown-first-run-fails.rlog");
        assertThrows(TheScenarioBodyFailed.class, () -> ScenarioRunner.run("Fails", STUB_CONFIG, failingTimer,
                100, 0.02, failed, deltaTime -> {
                    Logger.recordOutput("Test/field0", failingTimer.time());
                    throw new TheScenarioBodyFailed();
                }));

        // A clean run immediately afterwards must produce a whole, trustworthy log. This is the observable
        // consequence of the leak, and the reason the fix is worth having beyond tidiness.
        FakeTimer cleanTimer = new FakeTimer();
        Path clean = Paths.get("build", "sim", "shutdown-second-run-clean.rlog");
        ScenarioRunner.run("Clean", STUB_CONFIG, cleanTimer, 50, 0.02, clean,
                deltaTime -> Logger.recordOutput("Test/field0", cleanTimer.time()));

        assertEquals(50, RlogDecodedCompare.decode(clean).size(),
                "the run after a failed one still decodes to one frame per tick");
    }
}
