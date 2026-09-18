package org.horizon36596.simloop.loop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the scoring half of E1's Contract: the objective's one-place negation, the envelope's infinite
 * penalty, the breakdown, and the clause the whole batch turns on — a guardrail that cannot evaluate
 * itself returns {@code UNKNOWN}, never zero penalty.
 */
public class ScorerTest {

    private static final String FINISH_TIME = "RealOutputs/Auto/finishTimeSec";
    private static final String DRIVE_OUTPUT = "RealOutputs/Drive/output";

    /**
     * Contract: a minimizing objective is negated so bigger is always better, and the negation happens in
     * exactly one place. Invariant tier.
     *
     * <p>Pinned by behavior, not by implementation: of two runs, the one with the <i>smaller</i> raw
     * metric must score <i>higher</i>. Any reimplementation that gets the orientation right passes; a
     * second, cancelling sign flip anywhere fails.
     */
    @Test
    public void aMinimizingObjectiveMakesTheSmallerRunScoreHigher() throws IOException {
        RunResult fast = runWithFinishTime("fast", 12.0);
        RunResult slow = runWithFinishTime("slow", 30.0);
        Scorer scorer = new Scorer(finishTimeObjective(), Collections.emptyList());

        Scorer.Score fastScore = scorer.score(fast);
        Scorer.Score slowScore = scorer.score(slow);

        assertTrue(fastScore.total() > slowScore.total(),
                "a minimizing objective must make the faster run score higher; fast=" + fastScore.total()
                        + " slow=" + slowScore.total());
        assertEquals(12.0, fastScore.rawMetric(), 1e-9, "the raw metric keeps its own units and sign");
        assertEquals(-12.0, fastScore.objectiveValue(), 1e-9);
    }

    /**
     * Contract: the other half of the same clause — a MAXIMIZE objective is <b>not</b> negated, and a
     * bigger raw metric scores higher. Invariant tier.
     *
     * <p>Worth its own test because a double negation on the maximize path would leave the minimize test
     * above perfectly green while the loop optimized the wrong way.
     */
    @Test
    public void aMaximizingObjectiveIsNotNegatedAndMakesTheBiggerRunScoreHigher() throws IOException {
        RunResult few = runWithFinishTime("few-points", 8.0);
        RunResult many = runWithFinishTime("many-points", 42.0);
        Scorer scorer = new Scorer(
                Objective.ofFinalValue("points scored", "auto", FINISH_TIME,
                        Objective.Direction.MAXIMIZE, Objective.Budget.unstated()),
                Collections.emptyList());

        Scorer.Score fewScore = scorer.score(few);
        Scorer.Score manyScore = scorer.score(many);

        assertEquals(42.0, manyScore.objectiveValue(), 1e-9, "a maximizing objective must pass through unnegated");
        assertEquals(42.0, manyScore.rawMetric(), 1e-9);
        assertTrue(manyScore.total() > fewScore.total(), "more must score higher when maximizing");
    }

    /**
     * <b>The clause the batch turns on.</b> A guardrail that cannot evaluate itself from the run returns
     * UNKNOWN and is reported as such — it must never be arithmetically indistinguishable from one that
     * passed. Invariant tier.
     */
    @Test
    public void aGuardrailThatCannotCheckItselfIsReportedUnknownNotPassed() throws IOException {
        RunResult run = runWithFinishTime("unknowns", 12.0);
        Scorer scorer = new Scorer(finishTimeObjective(), Arrays.asList(
                EnvelopeGuardrails.noNonFiniteValues(),
                EnvelopeGuardrails.invariantTestsGreen(),
                EnvelopeGuardrails.passedInSimFirst(),
                EnvelopeGuardrails.forwardInverseKinematicsRoundTrip()));

        Scorer.Score score = scorer.score(run);

        assertEquals(3, score.unknownCount(), "three of these four cannot be answered from a log");
        assertTrue(score.unknowns().contains("invariantTestsGreen"));
        assertTrue(score.unknowns().contains("passedInSimFirst"));
        assertTrue(score.unknowns().contains("forwardInverseKinematicsRoundTrip"));
        assertFalse(score.hasViolation(), "UNKNOWN is not a violation");
        assertEquals(Guardrail.Status.HOLDS,
                score.verdicts().get("noNonFiniteValues").status(),
                "the one term that IS a log fact must be answered, not shrugged off as unknown");
        assertEquals(Guardrail.Status.UNKNOWN,
                score.verdicts().get("invariantTestsGreen").status());
        assertTrue(score.breakdown().contains("UNKNOWN"),
                "a human reading the breakdown must see that the envelope is guarding less than it looks "
                        + "like: " + score.breakdown());
    }

    /**
     * Contract: an envelope violation returns an effectively infinite penalty, which makes the run
     * ineligible regardless of how good its objective was. Invariant tier.
     */
    @Test
    public void anEnvelopeViolationBeatsAnyObjective() throws IOException {
        RunResult brilliantButBroken = runWithFinishTimeAndOutput("broken", 0.1, Double.NaN);
        RunResult mediocreButSound = runWithFinishTimeAndOutput("sound", 45.0, 0.4);
        Scorer scorer = new Scorer(finishTimeObjective(),
                Collections.singletonList(EnvelopeGuardrails.noNonFiniteValues()));

        Scorer.Score brokenScore = scorer.score(brilliantButBroken);
        Scorer.Score soundScore = scorer.score(mediocreButSound);

        assertTrue(brokenScore.hasViolation());
        assertTrue(brokenScore.violations().contains("noNonFiniteValues"),
                "the breakdown must name which term was broken: " + brokenScore.violations());
        assertEquals(Double.NEGATIVE_INFINITY, brokenScore.total(),
                "a violated envelope term must make the run ineligible, not merely expensive");
        assertTrue(soundScore.total() > brokenScore.total(),
                "a much worse but sound run must still beat a violating one");
    }

    /**
     * Contract: the scorer reports the breakdown, not just the total. Invariant tier — a total with no
     * breakdown is a number nobody can argue with.
     */
    @Test
    public void theScoreCarriesEveryTermThatProducedIt() throws IOException {
        RunResult run = runWithFinishTimeAndOutput("breakdown", 20.0, 0.5);
        Scorer scorer = new Scorer(finishTimeObjective(), Arrays.asList(
                EnvelopeGuardrails.noNonFiniteValues(),
                EnvelopeGuardrails.runIsComplete(),
                EnvelopeGuardrails.invariantTestsGreen()));

        Scorer.Score score = scorer.score(run);

        assertEquals(3, score.verdicts().size(), "every guardrail applied must appear in the breakdown");
        for (Guardrail.Verdict verdict : score.verdicts().values()) {
            assertFalse(verdict.reason().trim().isEmpty(), "every term must say why");
        }
    }

    /**
     * Contract: {@code score = objective − Σ penalties}, and the penalties actually add up. Invariant
     * tier.
     *
     * <p>The expected total is written out by hand — {@code -20.0 - (2.0 + 3.0) = -25.0} — rather than
     * recomputed as {@code objectiveValue() - totalPenalty()}. That second form is the expression
     * {@code total()} itself uses, so it would pass even if both sides were wrong in the same way, which
     * is the definition of a circular test.
     */
    @Test
    public void severalPenaltiesAreSummedAndSubtractedFromTheObjective() throws IOException {
        RunResult run = runWithFinishTimeAndOutput("penalty-sum", 20.0, 0.5);
        Scorer scorer = new Scorer(finishTimeObjective(), Arrays.asList(
                softBudget("firstBudget", 2.0),
                softBudget("secondBudget", 3.0)));

        Scorer.Score score = scorer.score(run);

        assertEquals(-20.0, score.objectiveValue(), 1e-9, "a 20s finish time, minimized, negates to -20.0");
        assertEquals(5.0, score.totalPenalty(), 1e-9, "2.0 + 3.0");
        assertEquals(-25.0, score.total(), 1e-9, "-20.0 minus a total penalty of 5.0, computed by hand");
        assertTrue(score.verdicts().get("firstBudget").isOverBudget());
        assertFalse(score.hasViolation(), "a soft budget is not an envelope violation");
    }

    /**
     * Contract: a short log is a violation, not a low score. Invariant tier — "win by not logging" is on
     * {@code scorer-interface.md} §7's checklist.
     */
    @Test
    public void aShortLogIsAViolationRatherThanACheaperRun() throws IOException {
        Path rlog = LoopTestRlogs.writeOneKeyPerFrame("loop-scorer-truncated", FINISH_TIME, 5.0, 5.0);
        RunResult truncated = RunResult.fromRlog("Truncated", rlog, 50);
        Scorer scorer = new Scorer(finishTimeObjective(),
                Collections.singletonList(EnvelopeGuardrails.runIsComplete()));

        Scorer.Score score = scorer.score(truncated);

        assertTrue(score.hasViolation());
        assertEquals(Double.NEGATIVE_INFINITY, score.total());
    }

    /**
     * Contract: every metric the scorer resolves is a real key in a real RLOG, and asking for one that is
     * not there fails loudly. Invariant tier.
     */
    @Test
    public void anObjectiveNamingAMissingKeyFailsLoudly() throws IOException {
        RunResult run = runWithFinishTime("missing", 12.0);
        Objective typo = Objective.ofFinalValue("typo'd objective", "auto",
                "RealOutputs/Auto/finishTimeSeconds", Objective.Direction.MINIMIZE,
                Objective.Budget.unstated());
        Scorer scorer = new Scorer(typo, Collections.emptyList());

        assertThrows(RunResult.MetricNotFoundException.class, () -> scorer.score(run));
    }

    /**
     * Contract: a non-finite objective throws instead of being passed through. Invariant tier — every
     * comparison against {@code NaN} is false, so a loop scoring one would silently stop accepting
     * improvements and look exactly like a loop that had legitimately plateaued. That failure is
     * indistinguishable from success from the outside, which is why it must be loud from the inside.
     */
    @Test
    public void aNonFiniteObjectiveThrowsRatherThanSilentlyPlateauingTheLoop() throws IOException {
        RunResult run = runWithFinishTimeAndOutput("nan-objective", Double.NaN, 0.0);
        Scorer scorer = new Scorer(finishTimeObjective(), Collections.emptyList());

        Objective.NonFiniteMetricException thrown = assertThrows(Objective.NonFiniteMetricException.class,
                () -> scorer.score(run));

        assertTrue(thrown.getMessage().contains("auto finish time"),
                "the failure must name the objective that measured badly: " + thrown.getMessage());
    }

    /**
     * Contract: two scores are only comparable when they measure the same objective over the same
     * scenario. Invariant tier — comparing across scenarios is how a loop reports an improvement it did
     * not make, and that stays true under any redesign of how scores are computed.
     */
    @Test
    public void scoresFromDifferentScenariosAreNotComparable() throws IOException {
        RunResult run = runWithFinishTime("scenarios", 12.0);
        Scorer autoScorer = new Scorer(finishTimeObjective(), Collections.emptyList());
        Scorer teleopScorer = new Scorer(Objective.ofFinalValue("auto finish time", "teleop-sprint",
                FINISH_TIME, Objective.Direction.MINIMIZE,
                Objective.Budget.unstated()), Collections.emptyList());

        Scorer.Score autoScore = autoScorer.score(run);
        Scorer.Score teleopScore = teleopScorer.score(run);

        assertFalse(autoScore.isComparableTo(teleopScore));
        assertTrue(autoScore.isComparableTo(autoScorer.score(run)));
    }

    /**
     * Contract: a guardrail that returns {@code null} is rejected, not read as "fine". Invariant tier —
     * this is the UNKNOWN clause's back door: a null that scored as zero penalty would be exactly the
     * silent pass the whole design refuses.
     */
    @Test
    public void aGuardrailReturningNullIsRejectedRatherThanReadAsFine() throws IOException {
        RunResult run = runWithFinishTime("null-verdict", 12.0);
        Scorer scorer = new Scorer(finishTimeObjective(), Collections.singletonList(new Guardrail() {
            @Override
            public String name() {
                return "returnsNull";
            }

            @Override
            public Verdict evaluate(RunResult ignored) {
                return null;
            }
        }));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> scorer.score(run));

        assertTrue(thrown.getMessage().contains("returnsNull"),
                "the failure must name the guardrail at fault: " + thrown.getMessage());
    }

    /**
     * Contract: two guardrails sharing a name are rejected. Invariant tier — names are how the breakdown
     * is read, so a silent overwrite would drop a whole envelope term out of the audit without anything
     * saying so.
     */
    @Test
    public void twoGuardrailsWithTheSameNameAreRejectedRatherThanOverwritingEachOther() throws IOException {
        RunResult run = runWithFinishTime("duplicate-names", 12.0);
        Scorer scorer = new Scorer(finishTimeObjective(),
                Arrays.asList(softBudget("sameName", 1.0), softBudget("sameName", 2.0)));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> scorer.score(run));

        assertTrue(thrown.getMessage().contains("sameName"),
                "the failure must name the collision: " + thrown.getMessage());
    }

    /**
     * The max- and mean-valued objective factories read the metric they say they do. Implementation tier
     * — {@code scorer-interface.md} §2 names {@code path.maxCrossTrackErrorIn} and {@code loop.meanPeriodMs}
     * as worked examples, so both aggregations are spec'd API rather than spare parts.
     */
    @Test
    public void theMaxAndMeanObjectiveFactoriesReadTheirOwnAggregation() throws IOException {
        Path rlog = LoopTestRlogs.writeOneKeyPerFrame("loop-scorer-aggregates", FINISH_TIME, 2.0, 6.0, 4.0);
        RunResult run = RunResult.fromRlog("Aggregates", rlog, 3);

        Objective worstError = Objective.ofMaxValue("worst cross-track error", "auto", FINISH_TIME,
                Objective.Direction.MINIMIZE, Objective.Budget.unstated());
        Objective meanPeriod = Objective.ofMeanValue("mean loop period", "auto", FINISH_TIME,
                Objective.Direction.MINIMIZE, Objective.Budget.unstated());

        assertEquals(6.0, worstError.rawMetric(run), 1e-9, "max of 2, 6, 4");
        assertEquals(4.0, meanPeriod.rawMetric(run), 1e-9, "mean of 2, 6, 4");
        assertEquals(-6.0, worstError.value(run), 1e-9, "and both still negate when minimizing");
    }

    /**
     * A soft budget is graded and tradeable; an envelope violation is not. Rejecting an infinite "soft"
     * penalty keeps the two from blurring. Implementation tier.
     */
    @Test
    public void aSoftBudgetPenaltyCannotBeInfinite() {
        assertThrows(IllegalArgumentException.class,
                () -> Guardrail.Verdict.overBudget(Double.POSITIVE_INFINITY, "too slow"));
        assertThrows(IllegalArgumentException.class,
                () -> Guardrail.Verdict.overBudget(-1.0, "negative"));
    }

    /**
     * A graded soft-budget penalty is reported as OVER_BUDGET, not as HOLDS. Implementation tier — it
     * keeps a term that charged the score from reading, in the breakdown a human audits, as a term that
     * was fine.
     */
    @Test
    public void aSoftBudgetPenaltyIsNotReportedAsHolding() {
        Guardrail.Verdict verdict = Guardrail.Verdict.overBudget(2.5, "ran 2.5s over the soft budget");

        assertEquals(Guardrail.Status.OVER_BUDGET, verdict.status());
        assertEquals(2.5, verdict.penalty(), 1e-9);
        assertTrue(verdict.isOverBudget());
        assertFalse(verdict.holdsCleanly(), "it held as an invariant, but it did not hold cleanly");
        assertFalse(verdict.isViolation(), "a soft budget is tradeable; an envelope violation is not");
        assertFalse(verdict.isUnknown(), "it was checked, so it is not unknown");
    }

    /** An UNKNOWN verdict must say why, or nobody can tell whether the gap matters. Implementation tier. */
    @Test
    public void anUnknownVerdictMustSayWhyItCouldNotCheckItself() {
        assertThrows(IllegalArgumentException.class, () -> Guardrail.Verdict.unknown("  "));
    }

    private static Objective finishTimeObjective() {
        return Objective.ofFinalValue("auto finish time", "auto", FINISH_TIME,
                Objective.Direction.MINIMIZE, Objective.Budget.of("about an hour"));
    }

    /** A guardrail that always charges a fixed graded penalty, for testing the arithmetic. */
    private static Guardrail softBudget(String name, double penalty) {
        return new Guardrail() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Verdict evaluate(RunResult ignored) {
                return Verdict.overBudget(penalty, "a fixed test penalty of " + penalty);
            }
        };
    }

    private static RunResult runWithFinishTime(String name, double finishTimeSec) throws IOException {
        return runWithFinishTimeAndOutput(name, finishTimeSec, 0.0);
    }

    private static RunResult runWithFinishTimeAndOutput(String name, double finishTimeSec, double output)
            throws IOException {
        Path rlog = LoopTestRlogs.write("loop-scorer-" + name, table -> {
            table.put(FINISH_TIME, finishTimeSec);
            table.put(DRIVE_OUTPUT, output);
        });
        return RunResult.fromRlog(name, rlog, 1);
    }
}
