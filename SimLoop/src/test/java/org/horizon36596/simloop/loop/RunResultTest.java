package org.horizon36596.simloop.loop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code RunResult}'s share of E1's Contract.
 *
 * <p><b>Invariant tier</b> unless a test says otherwise — every clause here survives any redesign of how a
 * run is read. "A missing metric must not read as zero" and "a short log is a violation" are statements
 * about what the loop is allowed to believe, not about how {@code RunResult} is implemented.
 */
public class RunResultTest {

    private static final String SLIDE_POSITION = "RealOutputs/Slide/measuredIn";

    /**
     * Contract: a finished run is readable from an RLOG on disk, and every number comes from decoded
     * fields. Invariant tier.
     */
    @Test
    public void aRunIsReadBackFromItsDecodedFields() throws IOException {
        Path rlog = LoopTestRlogs.writeOneKeyPerFrame("loop-runresult-decoded", SLIDE_POSITION, 1.0, 2.0, 3.0);

        RunResult run = RunResult.fromRlog("DecodedRun", rlog, 3);

        assertEquals(3, run.frameCount());
        assertEquals(3.0, run.finalValue(SLIDE_POSITION), 1e-9);
        assertEquals(1.0, run.minValue(SLIDE_POSITION), 1e-9);
        assertEquals(3.0, run.maxValue(SLIDE_POSITION), 1e-9);
        assertEquals(2.0, run.meanValue(SLIDE_POSITION), 1e-9);
    }

    /**
     * Contract: asking for a key that is not there fails loudly with the key named. A typo'd metric must
     * never read as a legitimate zero. Invariant tier — this is the clause that keeps a broken objective
     * from looking like a bad run.
     */
    @Test
    public void aMetricThatWasNeverLoggedThrowsInsteadOfScoringZero() throws IOException {
        Path rlog = LoopTestRlogs.writeOneKeyPerFrame("loop-runresult-typo", SLIDE_POSITION, 1.0);
        RunResult run = RunResult.fromRlog("TypoRun", rlog, 1);

        RunResult.MetricNotFoundException thrown = assertThrows(RunResult.MetricNotFoundException.class,
                () -> run.metric("RealOutputs/Slide/measuredInches"));

        assertTrue(thrown.getMessage().contains("RealOutputs/Slide/measuredInches"),
                "the exception must name the key that was missing: " + thrown.getMessage());
    }

    /**
     * Contract: a failed parse is loud. A boolean or a state name silently parsed as 0.0 turns a broken
     * metric into a plausible score. Invariant tier.
     */
    @Test
    public void aNonNumericMetricThrowsRatherThanDefaultingToZero() throws IOException {
        Path rlog = LoopTestRlogs.write("loop-runresult-states",
                table -> table.put("RealOutputs/Slide/state", "HOLDING"));

        RunResult run = RunResult.fromRlog("StateRun", rlog, 1);

        assertThrows(RunResult.MetricNotNumericException.class,
                () -> run.metric("RealOutputs/Slide/state"));
    }

    /**
     * Contract: a short or missing log is a violation, not a low score. The run declares how many frames
     * it should have had, so the expected count is known before the run rather than inferred after it.
     * Invariant tier — this is the "win by not logging" defense.
     */
    @Test
    public void aRunShorterThanItsDeclaredTickCountIsIncomplete() throws IOException {
        Path rlog = LoopTestRlogs.writeOneKeyPerFrame("loop-runresult-short", SLIDE_POSITION, 1.0, 2.0);

        RunResult shortRun = RunResult.fromRlog("ShortRun", rlog, 10);
        RunResult fullRun = RunResult.fromRlog("FullRun", rlog, 2);

        assertFalse(shortRun.isComplete(), "2 frames against 10 expected must not read as complete");
        assertTrue(fullRun.isComplete());
    }

    /**
     * Contract: a <b>missing</b> log is a violation, not a low score. The short-log half is pinned above;
     * this is the other half, and it is the worse one — a scorer that read a missing file as zero frames
     * would compare two failed runs as equal and call it agreement. Invariant tier.
     */
    @Test
    public void aMissingRlogFailsLoudlyRatherThanReadingAsAnEmptyRun() throws IOException {
        Path neverWritten = LoopTestRlogs.simDir().resolve("loop-runresult-never-written.rlog");
        Files.deleteIfExists(neverWritten);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> RunResult.fromRlog("MissingRun", neverWritten, 10));

        assertTrue(thrown.getMessage().contains("loop-runresult-never-written.rlog"),
                "the failure must name the file that was not there: " + thrown.getMessage());
    }

    /**
     * Contract: a run that decoded no frames at all is rejected at construction. Invariant tier — with a
     * zero-frame run allowed through, every envelope term that scans frames holds vacuously, and "no NaN
     * was logged" stops meaning anything when nothing was logged.
     */
    @Test
    public void aRunThatDecodedNoFramesIsRejectedRatherThanScoredVacuously() throws IOException {
        Path emptyRun = LoopTestRlogs.write("loop-runresult-no-frames");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> RunResult.fromRlog("NoFrames", emptyRun, 10));

        assertTrue(thrown.getMessage().contains("zero frames"),
                "the failure must say the run had no frames: " + thrown.getMessage());
    }

    /**
     * Contract: {@code NaN}/{@code Inf} in any control output is visible. Invariant tier — it is one of
     * the few §3 envelope terms that genuinely is a log fact.
     */
    @Test
    public void nonFiniteValuesAreFoundAndNamed() throws IOException {
        Path rlog = LoopTestRlogs.write("loop-runresult-nan", table -> {
            table.put("RealOutputs/Drive/output", Double.NaN);
            table.put("RealOutputs/Slide/output", 0.5);
            table.put("RealOutputs/Slide/state", "HOLDING");
        });

        RunResult run = RunResult.fromRlog("NanRun", rlog, 1);

        assertTrue(run.hasNonFiniteValue());
        assertTrue(run.nonFiniteKeys().contains("RealOutputs/Drive/output"));
        assertFalse(run.nonFiniteKeys().contains("RealOutputs/Slide/output"),
                "a finite number must not be reported as non-finite");
        assertFalse(run.nonFiniteKeys().contains("RealOutputs/Slide/state"),
                "a string is not a number that went bad, so it must not be reported here");
    }

    /**
     * Contract: the NaN/Inf envelope term names <i>pose</i>, and a pose is routinely logged as one
     * array-valued field rather than three scalars — so a NaN sitting inside an array must still be
     * found. Invariant tier: this is the term failing silently on exactly the field it names.
     */
    @Test
    public void aNonFiniteNumberInsideAnArrayValuedFieldIsStillFound() throws IOException {
        Path rlog = LoopTestRlogs.write("loop-runresult-array-nan", table -> {
            table.put("RealOutputs/Drive/pose", new double[] {12.0, Double.NaN, 0.5});
            table.put("RealOutputs/Drive/velocity", new double[] {1.0, 2.0, 3.0});
        });

        RunResult run = RunResult.fromRlog("ArrayNanRun", rlog, 1);

        assertTrue(run.nonFiniteKeys().contains("RealOutputs/Drive/pose"),
                "a NaN inside an array-valued pose must not hide behind the array; found "
                        + run.nonFiniteKeys());
        assertFalse(run.nonFiniteKeys().contains("RealOutputs/Drive/velocity"),
                "a clean array must not be reported as non-finite");
    }

    /**
     * A string field that merely contains the letters "NaN" is not a number that went bad. Implementation
     * tier — it pins the token boundaries the array scan needs to stay honest in the other direction, so
     * the envelope does not start rejecting good runs over a state name.
     */
    @Test
    public void aStateNameContainingTheLettersNanIsNotReportedAsNonFinite() throws IOException {
        Path rlog = LoopTestRlogs.write("loop-runresult-nanoscale",
                table -> table.put("RealOutputs/Slide/state", "NaNoScale"));

        RunResult run = RunResult.fromRlog("NanoScaleRun", rlog, 1);

        assertFalse(run.hasNonFiniteValue(),
                "'NaNoScale' is a state name, not a broken number: " + run.nonFiniteKeys());
    }

    /**
     * Contract: ceasing to log an output does not hide it. Invariant tier — "win by not logging" is the
     * cheapest reward hack available, and this is the shape of it that is easiest to imagine: drive the
     * robot, stop publishing the output key, let the last good number stand.
     *
     * <p>It does not work, and the reason is the log format rather than anything this package does. An
     * RLOG is <b>delta-encoded</b>: PsiKit writes a field only when its value changes, and replay applies
     * those changes to one running table, so a decoded frame is a snapshot of the whole robot at that
     * cycle. A field that stops being written simply persists at its last value — which is exactly what
     * re-writing that value would have produced.
     *
     * <p>Pinned as a test because it was first assumed the other way round: the reviewer who raised this
     * hack, and the fix written for it, were both reasoning from "not written means absent". Checking the
     * decoder settled it. A behavior that a whole safety argument leans on should be red in CI if it ever
     * changes, not re-derived from memory next time.
     */
    @Test
    public void aFieldThatStopsBeingLoggedPersistsAtItsLastValueRatherThanVanishing() throws IOException {
        Path rlog = LoopTestRlogs.write("loop-runresult-stops-logging",
                table -> {
                    table.put("RealOutputs/Drive/output", 0.9);
                    table.put("RealOutputs/Robot/heartbeat", 1.0);
                },
                table -> table.put("RealOutputs/Robot/heartbeat", 2.0));

        RunResult run = RunResult.fromRlog("StopsLoggingRun", rlog, 2);

        assertEquals(2, run.metric("RealOutputs/Drive/output").size(),
                "the output was written once but must decode in both frames -- delta encoding carries it "
                        + "forward, so there is no gap for a candidate to hide in");
        assertEquals(0.9, run.finalValue("RealOutputs/Drive/output"), 1e-9,
                "and the final frame still carries it, so finalValue is the value the run ended on");
    }

    /**
     * The run's frames cannot be edited after the fact, inner maps included. Implementation tier — it
     * pins the "immutable" the class javadoc claims, so no caller can quietly rewrite the evidence a
     * score was computed from.
     */
    @Test
    public void aRunsFramesCannotBeEditedByACaller() throws IOException {
        Path rlog = LoopTestRlogs.writeOneKeyPerFrame("loop-runresult-immutable", SLIDE_POSITION, 1.0);
        RunResult run = RunResult.fromRlog("ImmutableRun", rlog, 1);

        assertThrows(UnsupportedOperationException.class,
                () -> run.frames().get(0).put(SLIDE_POSITION, "999.0"));
        assertThrows(UnsupportedOperationException.class, () -> run.keys().add("invented/key"));
    }

    /**
     * A run that declares zero expected frames cannot be scored, and saying so beats defaulting — with a
     * zero default every short run would read as complete. Implementation tier.
     */
    @Test
    public void aRunMustDeclareAPositiveExpectedFrameCount() throws IOException {
        Path rlog = LoopTestRlogs.writeOneKeyPerFrame("loop-runresult-zero", SLIDE_POSITION, 1.0);

        assertThrows(IllegalArgumentException.class, () -> RunResult.fromRlog("ZeroRun", rlog, 0));
    }
}
