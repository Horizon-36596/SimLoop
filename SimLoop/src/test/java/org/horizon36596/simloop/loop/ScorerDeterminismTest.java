package org.horizon36596.simloop.loop;

import org.horizon36596.simloop.config.DrivetrainSimConfig;
import org.horizon36596.simloop.config.SimRobotConfig;
import org.horizon36596.simloop.sim.FakeTimer;
import org.horizon36596.simloop.sim.RlogDecodedCompare;
import org.horizon36596.simloop.sim.ScenarioRunner;

import org.junit.jupiter.api.Test;
import org.psilynx.psikit.core.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins domain <b>R5</b> where the loop actually depends on it: the same scenario, run twice on a
 * {@link FakeTimer}, must produce the <b>same score</b>, not merely a similar one.
 *
 * <p>Determinism is already pinned for the RLOG itself ({@code ScenarioRunnerTest}). This pins the next
 * link in the chain, which is the one the loop's acceptance gate leans on: {@code scorer-interface.md}
 * §6.3 promotes a candidate only when {@code score(candidate) > score(last-best)}. If scoring the same
 * run twice could wobble by even an ULP, that comparison would sometimes fire on noise, and the loop
 * would "improve" without any edit having helped. So the assertion here is exact equality with zero
 * tolerance, on purpose, a tolerance would be the very slack this test exists to deny.
     *
     * <h2>What is deliberately NOT tested here, and what was learned trying</h2>
     * BACKLOG B3's clause -- "compare decoded fields, never raw bytes" -- has no test in this class,
     * after two attempts to write one failed honestly:
     * <ul>
     *   <li>Running the same scenario twice and asserting the bytes differ: measured on this JVM, only
     *       the <i>first</i> scenario in a test run produces different bytes; every later pair came out
     *       byte-identical. Guarding that with an assumption produced a test that skipped almost always,
     *       which reads as coverage while pinning nothing.</li>
     *   <li>Writing the same two fields in opposite {@code put} order: produces byte-identical files, so
     *       {@code LogTable}'s key insertion order does not reach the encoder at all.</li>
     * </ul>
     * Neither result matches B3's stated mechanism ("PsiKit encodes log keys in hash-map iteration
     * order"). The variation that <i>was</i> observed is consistent with PsiKit's own writer-thread
     * metadata on first use -- the two keys {@code RlogDecodedCompare} already excludes by name. That is
     * a finding about B3's wording and about area B's decoder, not something for this batch to rewrite,
     * so it is recorded in the checkpoint instead. The decoded-field contract itself stays pinned by
     * {@code RlogDecodedCompareTest} and by the scenario test below.
     */
public class ScorerDeterminismTest {

    private static final double DT = 0.02;
    private static final int TICKS = 25;

    /** A minimal stub, not a real robot: this module is season-agnostic core (R7). */
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
     * <b>Contract, invariant tier (R5).</b> Two runs of one scenario on {@code FakeTimer} score bit-identically.
     *
     * <p>Checked at three depths so a failure says which link broke: the decoded RLOGs match (the run is
     * deterministic), the objective's raw metric matches (the metric reads deterministically), and the
     * totals match (nothing in scoring introduced its own wobble).
     */
    @Test
    public void theSameScenarioRunTwiceScoresIdentically() throws IOException {
        Path first = runScenario("loop-determinism-1");
        Path second = runScenario("loop-determinism-2");

        List<RlogDecodedCompare.Mismatch> mismatches = RlogDecodedCompare.compare(first, second);
        assertTrue(mismatches.isEmpty(),
                "the two runs must decode identically before their scores can mean anything: " + mismatches);

        Scorer scorer = new Scorer(
                Objective.ofFinalValue("distance travelled", "determinism-scenario",
                        "RealOutputs/Test/distanceIn", Objective.Direction.MAXIMIZE,
                        Objective.Budget.unstated()),
                Arrays.asList(
                        EnvelopeGuardrails.noNonFiniteValues(),
                        EnvelopeGuardrails.runIsComplete(),
                        EnvelopeGuardrails.invariantTestsGreen()));

        Scorer.Score firstScore = scorer.score(RunResult.fromRlog("Determinism1", first, TICKS));
        Scorer.Score secondScore = scorer.score(RunResult.fromRlog("Determinism2", second, TICKS));

        assertEquals(firstScore.rawMetric(), secondScore.rawMetric(), 0.0,
                "the raw metric must read the same twice");
        assertEquals(firstScore.total(), secondScore.total(), 0.0,
                "zero tolerance on purpose: a score that wobbles lets the gate promote a candidate that "
                        + "did not actually improve");

        // Not just the scalars. Two runs could total the same while disagreeing about WHICH term was
        // unknown or what the breakdown said -- a guardrail whose name came from the JVM's idea of a
        // lambda class would do exactly that. The breakdown is the artifact a human audits, so it is the
        // thing that has to be stable.
        assertEquals(firstScore.verdicts().keySet(), secondScore.verdicts().keySet(),
                "the same envelope terms must be reported, under the same names, in both runs");
        assertEquals(firstScore.unknowns(), secondScore.unknowns(),
                "the envelope must report the same gaps twice, or a breakdown means nothing");
        assertEquals(firstScore.violations(), secondScore.violations());
        assertEquals(firstScore.breakdown(), secondScore.breakdown(),
                "the human-readable breakdown must be identical text for two identical runs");
    }

    /**
     * Runs one fixed scenario end-to-end and returns its RLOG. Every number it logs comes from the
     * injected {@link FakeTimer}, never from a wall clock (R5).
     */
    private static Path runScenario(String baseName) throws IOException {
        Path dir = Paths.get("build", "sim");
        Files.createDirectories(dir);
        Path rlogPath = dir.resolve(baseName + ".rlog");
        FakeTimer timer = new FakeTimer();

        ScenarioRunner.run("ScorerDeterminismTest", STUB_CONFIG, timer, TICKS, DT, rlogPath,
                deltaTimeSeconds -> {
                    // A stand-in for a robot: distance under a constant 30 in/s, integrated off sim time.
                    Logger.recordOutput("Test/distanceIn", timer.time() * 30.0);
                    Logger.recordOutput("Test/timeSec", timer.time());
                });
        return rlogPath;
    }
}
