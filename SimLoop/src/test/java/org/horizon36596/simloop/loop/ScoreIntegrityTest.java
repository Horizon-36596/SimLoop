package org.horizon36596.simloop.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.horizon36596.simloop.sim.RlogDecodedCompare;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pins the three ways the scorer could report a guarantee it had not checked, all found by the 2026-09-17
 * adversarial review.
 *
 * <p>The {@code loop} package referees automated code changes, so a term that cannot fail is worse here than
 * a missing term: a human reads a guardrail name in a breakdown and takes it as a check that ran.
 */
class ScoreIntegrityTest {

    // ---- guardrail-nan-thresholds-pass: a threshold that disables its own comparison ----

    @Test
    void aGuardrailCannotBeBuiltWithANonFiniteThreshold() {
        // NaN loses every comparison, so withinBound(key, NaN, NaN) used to construct happily and then hold
        // for every finite value an actuator could log — a guardrail that could not fail.
        assertThrows(IllegalArgumentException.class,
                () -> EnvelopeGuardrails.withinBound("Slide/inches", Double.NaN, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> EnvelopeGuardrails.withinBound("Slide/inches", 0.0, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> EnvelopeGuardrails.loopTimeUnderBudget("Loop/millis", Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> EnvelopeGuardrails.loopTimeUnderBudget("Loop/millis", Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> EnvelopeGuardrails.safeStopAtEnd("Drive/power", Double.NaN));
    }

    @Test
    void aGuardrailCannotBeBuiltWithANegativeBudgetOrTolerance() {
        assertThrows(IllegalArgumentException.class,
                () -> EnvelopeGuardrails.loopTimeUnderBudget("Loop/millis", -1.0));
        assertThrows(IllegalArgumentException.class,
                () -> EnvelopeGuardrails.safeStopAtEnd("Drive/power", -0.01));
    }

    @Test
    void theRejectionMessageSaysWhyRatherThanJustThatItRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> EnvelopeGuardrails.withinBound("Slide/inches", Double.NaN, 10.0));

        assertTrue(refused.getMessage().contains("minAllowed"), "it names the parameter at fault");
        assertTrue(refused.getMessage().contains("Slide/inches"), "and the key, so the caller can find it");
    }

    // ---- guardrail-identity-by-name: a term loosened without being dropped ----

    @Test
    void aGuardrailsNameCarriesItsThresholdsSoALoosenedBoundReadsAsADifferentTerm() {
        // Gate proves the envelope was retained by comparing guardrail NAMES. When the name was just
        // "withinBound(Slide/inches)", a candidate could replace a 10-inch limit with a 10,000-inch one,
        // drop nothing by name, and be reported as holding the same envelope.
        String tight = EnvelopeGuardrails.withinBound("Slide/inches", 0.0, 10.0).name();
        String loose = EnvelopeGuardrails.withinBound("Slide/inches", 0.0, 10_000.0).name();

        assertFalse(tight.equals(loose), "loosening the bound must change the name");
        assertTrue(tight.contains("10.0"), "the name states the bound it enforces: " + tight);

        assertFalse(EnvelopeGuardrails.loopTimeUnderBudget("Loop/millis", 20.0).name()
                        .equals(EnvelopeGuardrails.loopTimeUnderBudget("Loop/millis", 2000.0).name()),
                "same for a loop-time budget");
        assertFalse(EnvelopeGuardrails.safeStopAtEnd("Drive/power", 0.01).name()
                        .equals(EnvelopeGuardrails.safeStopAtEnd("Drive/power", 5.0).name()),
                "and for a safe-stop tolerance");
    }

    // ---- objective-callback-evaluated-twice: one run, two different numbers ----

    @Test
    void scoringMeasuresTheRunExactlyOnce() throws Exception {
        AtomicInteger timesMeasured = new AtomicInteger();
        Objective counting = Objective.of("counted", "any-scenario", Objective.Direction.MAXIMIZE,
                Objective.Budget.of("a test"), run -> timesMeasured.incrementAndGet());

        RunResult run = aTinyRun("loop-integrity-measured-once");
        Scorer.Score score = new Scorer(counting, Collections.emptyList()).score(run);

        assertEquals(1, timesMeasured.get(),
                "the metric is a caller-supplied function: calling it twice let a stateful one report "
                        + "different numbers as the objective and as the raw metric in one breakdown");
        assertEquals(1.0, score.rawMetric(), 1e-9, "and the one measurement is what gets reported");
        assertEquals(1.0, score.objectiveValue(), 1e-9);
    }

    @Test
    void anObjectiveThatMeasuresNonFiniteIsStillRejected() throws Exception {
        Objective broken = Objective.of("broken", "any-scenario", Objective.Direction.MAXIMIZE,
                Objective.Budget.of("a test"), run -> Double.NaN);

        RunResult run = aTinyRun("loop-integrity-nonfinite");

        // Measuring once must not have weakened the check that used to live in Objective.value.
        assertThrows(Objective.NonFiniteMetricException.class,
                () -> new Scorer(broken, Collections.emptyList()).score(run));
    }

    // ---- replay-skips-scoreable-fields: a field the replay check cannot see must not be scoreable ----

    @Test
    void theFieldsTheReplayCheckIgnoresAreNotVisibleToAScore() throws Exception {
        // RlogDecodedCompare drops these two keys before comparing two runs, because PsiKit writes them from
        // its own state and they differ between identical runs. A key the replay check cannot see but a
        // score CAN see is a metric that changes without anything about the robot changing -- the loop would
        // chase it, and a determinism check would say the run reproduced.
        Path rlog = LoopTestRlogs.write("loop-integrity-excluded-keys", table -> {
            table.put("Test/field0", 1.0);
            table.put("RealOutputs/Console", "some console text");
            table.put("RealOutputs/Logger/QueuedCycles", 7.0);
        });
        RunResult run = RunResult.fromRlog("loop-integrity-excluded-keys", rlog, 1);

        for (String excluded : RlogDecodedCompare.EXCLUDED_KEYS) {
            assertFalse(run.hasKey(excluded),
                    "'" + excluded + "' is invisible to the replay check, so it must be invisible here too");
            assertFalse(run.frames().get(0).containsKey(excluded),
                    "and it must be gone from the frames themselves, not only from keys()");
        }
        assertTrue(run.hasKey("Test/field0"),
                "while an ordinary logged field is still scoreable -- the check above is not vacuous");
    }

    // ---- guardrail-identity-by-name: a re-tuned threshold is a DIFFERENT guardrail, deliberately ----

    @Test
    void aGuardrailsIdentityIncludesItsThresholds() {
        // Gate proves the envelope was retained by comparing guardrail names, so the numbers have to be in
        // the name or a candidate can loosen a bound and still match. The accepted cost is that tightening
        // one also reads as a different guardrail; Gate's verdict says so. Pinning it here means neither
        // half can be changed by accident.
        String loose = EnvelopeGuardrails.withinBound("Slide/inches", 0.0, 40.0).name();
        String tight = EnvelopeGuardrails.withinBound("Slide/inches", 0.0, 30.0).name();
        String same = EnvelopeGuardrails.withinBound("Slide/inches", 0.0, 40.0).name();

        assertNotEquals(loose, tight, "a changed bound is a changed guardrail, whichever way it moved");
        assertEquals(loose, same, "while the same bound is the same guardrail, run after run");
        assertTrue(loose.contains("Slide/inches") && loose.contains("40.0"),
                "and the name is readable by a human in a breakdown: " + loose);

        assertNotEquals(EnvelopeGuardrails.loopTimeUnderBudget("Loop/ms", 20.0).name(),
                EnvelopeGuardrails.loopTimeUnderBudget("Loop/ms", 15.0).name(),
                "same rule for the loop-time budget");
        assertNotEquals(EnvelopeGuardrails.safeStopAtEnd("Drive/power", 0.01).name(),
                EnvelopeGuardrails.safeStopAtEnd("Drive/power", 0.05).name(),
                "and for the safe-stop tolerance");
    }

    /** One frame with one field: enough to be a scoreable run, small enough to say nothing else. */
    private static RunResult aTinyRun(String baseName) throws Exception {
        Path rlog = LoopTestRlogs.write(baseName, table -> table.put("Test/field0", 1.0));
        return RunResult.fromRlog(baseName, rlog, 1);
    }
}
