package org.horizon36596.simloop.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.horizon36596.simloop.config.DrivetrainSimConfig;
import org.horizon36596.simloop.config.SimRobotConfig;

import org.junit.jupiter.api.Test;
import org.psilynx.psikit.core.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Pins Contract clause 1 (area B, {@code docs/process/parallel-chat-prompts.md}): a scenario runs headless
 * from one {@link ScenarioRunner#run} call -- naming the OpMode, the robot's sim config, and a duration --
 * writes one RLOG at a caller-chosen path with no bespoke per-test Logger/tick-loop code (this test file has
 * none), and rerunning the same scenario produces decoded-field-identical RLOGs (R5, via
 * {@link RlogDecodedCompare}). Season-agnostic: uses a minimal stub {@link SimRobotConfig}, not a real robot.
 *
 * <p>Writes into {@code build/sim} rather than a JUnit {@code @TempDir}: PsiKit's RLOG writer thread does
 * not release its file handle the instant {@code Logger.end()} returns, and on Windows a still-open handle
 * makes JUnit's post-test {@code @TempDir} cleanup fail with {@code FileSystemException} (observed running
 * this exact test) -- the same reason every other sim test in this repo writes to {@code build/sim} instead
 * of a temp directory.
 */
class ScenarioRunnerTest {

    private static final double DT = 0.02;

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

    @Test
    void runsHeadlessAndWritesOneRlogAtTheCallerChosenPath() throws IOException {
        Path rlogPath = Paths.get("build", "sim", "scenario-runner-test-1.rlog");
        FakeTimer timer = new FakeTimer();
        int ticks = 10;
        double[] loggedTimes = new double[ticks];
        int[] tickIndex = {0};

        ScenarioRunner.run("ScenarioRunnerTest", STUB_CONFIG, timer, ticks, DT, rlogPath, deltaTimeSeconds -> {
            loggedTimes[tickIndex[0]++] = timer.time();
            Logger.recordOutput("Test/time", timer.time());
        });

        // Decodes the RLOG independently of ScenarioRunner.run()'s own exists/non-empty guard (BACKLOG
        // B11): that guard only proves *some* bytes reached disk, not that every tick's fields decode
        // back out, so this checks decoded content instead of re-asserting the same postcondition.
        List<Map<String, String>> frames = RlogDecodedCompare.decode(rlogPath);
        assertEquals(ticks, frames.size(), "one decoded frame must exist per tick");
        assertEquals(loggedTimes[0], Double.parseDouble(frames.get(0).get("RealOutputs/Test/time")), 1e-9,
                "first frame must decode back the first tick's logged field");
        assertEquals(loggedTimes[ticks - 1],
                Double.parseDouble(frames.get(frames.size() - 1).get("RealOutputs/Test/time")), 1e-9,
                "last frame must decode back the last tick's logged field");
    }

    @Test
    void sameScenarioTwiceProducesDecodedFieldIdenticalRlogs() throws IOException {
        Path rlog1 = Paths.get("build", "sim", "scenario-runner-test-determinism-1.rlog");
        Path rlog2 = Paths.get("build", "sim", "scenario-runner-test-determinism-2.rlog");

        runCountingScenario(rlog1);
        assertTrue(!Logger.getReceiverQueueFault(), "writer queue must not overflow for run 1");
        runCountingScenario(rlog2);
        assertTrue(!Logger.getReceiverQueueFault(), "writer queue must not overflow for run 2");

        List<RlogDecodedCompare.Mismatch> mismatches = RlogDecodedCompare.compare(rlog1, rlog2);
        assertTrue(mismatches.isEmpty(),
                "identical scenarios must produce decoded-field-identical RLOGs, got: " + mismatches);
    }

    private static void runCountingScenario(Path rlogPath) {
        FakeTimer timer = new FakeTimer();
        int[] ticksSeen = {0};
        ScenarioRunner.run("CountingScenario", STUB_CONFIG, timer, 25, DT, rlogPath, deltaTimeSeconds -> {
            ticksSeen[0]++;
            Logger.recordOutput("Test/tickCount", ticksSeen[0]);
            Logger.recordOutput("Test/time", timer.time());
        });
    }
}
