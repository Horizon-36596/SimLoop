package org.horizon36596.simloop.loop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the clause E1 turns on, on the side of the envelope where it actually bites: a guardrail that
 * cannot evaluate itself <b>from this run</b> says {@code UNKNOWN}, never zero penalty — and a guardrail
 * that <i>can</i> check never lets a broken number read as fine.
 *
 * <p>{@code ScorerTest} pins the always-unanswerable case (a Gradle fact, a history fact). This class
 * pins the two harder ones: a term that <i>could</i> be checked in principle, handed a run that happens
 * not to log the key it needs; and a term handed a run whose number is {@code NaN}, where every ordinary
 * comparison silently returns false and reports success.
 */
public class EnvelopeGuardrailsTest {

    private static final String SLIDE_OUTPUT = "RealOutputs/Slide/output";
    private static final String DRIVE_OUTPUT = "RealOutputs/Drive/output";
    private static final String LOOP_TIME = "RealOutputs/Robot/loopTimeMs";

    /**
     * <b>Contract, invariant tier.</b> A guardrail whose key this run never logged returns UNKNOWN, not
     * HOLDS — and the same guardrail, handed a run that does log the key, answers properly. Both halves
     * matter: the first is the reward-hacking defense, the second proves the guardrail is not simply
     * UNKNOWN about everything, which would be a different way of guarding nothing.
     *
     * <p>Stated over behavior, not over {@code EnvelopeGuardrails}' internals: any reimplementation that
     * distinguishes "checked and fine" from "could not check" passes this.
     */
    @Test
    public void aGuardrailWhoseKeyThisRunNeverLoggedIsUnknownRatherThanPassing() throws IOException {
        RunResult runWithoutSlide = runLogging("no-slide", DRIVE_OUTPUT, 0.4);
        RunResult runWithSlide = runLogging("with-slide", SLIDE_OUTPUT, 0.4);
        Guardrail slideBound = EnvelopeGuardrails.withinBound(SLIDE_OUTPUT, -1.0, 1.0);

        Guardrail.Verdict unlogged = slideBound.evaluate(runWithoutSlide);
        Guardrail.Verdict logged = slideBound.evaluate(runWithSlide);

        assertEquals(Guardrail.Status.UNKNOWN, unlogged.status(),
                "an unlogged actuator is not a safe one; UNKNOWN is the only honest answer");
        assertEquals(0.0, unlogged.penalty(), 0.0, "UNKNOWN never invents a penalty either");
        assertTrue(unlogged.reason().contains(SLIDE_OUTPUT),
                "the reason must name the key nobody logged, or a human cannot close the gap: "
                        + unlogged.reason());
        assertTrue(logged.holdsCleanly(),
                "handed the key it needs, the same guardrail must answer rather than shrug");
    }

    /**
     * <b>Contract, invariant tier.</b> A bound that is genuinely exceeded is a violation with an
     * effectively infinite penalty — the envelope is a wall, not a price.
     */
    @Test
    public void anActuatorCommandedPastItsBoundIsAViolationNotAPenalty() throws IOException {
        RunResult overDriven = runLogging("over-bound", SLIDE_OUTPUT, 1.4);
        Guardrail slideBound = EnvelopeGuardrails.withinBound(SLIDE_OUTPUT, -1.0, 1.0);

        Guardrail.Verdict verdict = slideBound.evaluate(overDriven);

        assertTrue(verdict.isViolation());
        assertEquals(Double.POSITIVE_INFINITY, verdict.penalty(),
                "an envelope violation must be unbuyable, not merely expensive");
    }

    /**
     * <b>Contract, invariant tier.</b> A {@code NaN} must never read as "inside the bound".
     *
     * <p>This is the subtlest hole in the whole envelope. A single {@code NaN} frame poisons the run's
     * min and max, and <i>every</i> comparison against {@code NaN} is false — so the natural
     * {@code min < allowed || max > allowed} test reports that a run which commanded a non-finite value
     * to an actuator stayed neatly in bounds. Guarding nothing, while looking green.
     */
    @Test
    public void aNonFiniteActuatorValueCannotReadAsInsideItsBound() throws IOException {
        RunResult broken = runLogging("nan-bound", SLIDE_OUTPUT, Double.NaN);

        Guardrail.Verdict verdict = EnvelopeGuardrails.withinBound(SLIDE_OUTPUT, -1.0, 1.0).evaluate(broken);

        assertTrue(verdict.isViolation(),
                "NaN compares false against every bound, so it must be rejected before the comparison, "
                        + "not after it; got " + verdict);
    }

    /**
     * <b>Contract, invariant tier.</b> The same {@code NaN} hole, closed for the loop-time budget: a
     * non-finite loop time is not "under budget".
     */
    @Test
    public void aNonFiniteLoopTimeCannotReadAsUnderBudget() throws IOException {
        RunResult notANumber = runLogging("nan-loop-time", LOOP_TIME, Double.NaN);
        RunResult infinite = runLogging("inf-loop-time", LOOP_TIME, Double.POSITIVE_INFINITY);
        Guardrail budget = EnvelopeGuardrails.loopTimeUnderBudget(LOOP_TIME, 20.0);

        assertTrue(budget.evaluate(notANumber).isViolation(),
                "NaN is the case that needs the explicit check: NaN > 20.0 is false, so without it a "
                        + "broken loop time reads as comfortably inside budget; got "
                        + budget.evaluate(notANumber));
        assertTrue(budget.evaluate(infinite).isViolation(),
                "an infinite loop time is not inside a 20 ms budget either");
    }

    /**
     * <b>Contract, invariant tier.</b> The same {@code NaN} hole, closed for safe-stop: a non-finite final
     * output is not a stopped robot.
     */
    @Test
    public void aNonFiniteFinalOutputCannotReadAsSafelyStopped() throws IOException {
        RunResult broken = runLogging("nan-safe-stop", DRIVE_OUTPUT, Double.NaN);

        Guardrail.Verdict verdict = EnvelopeGuardrails.safeStopAtEnd(DRIVE_OUTPUT, 1e-6).evaluate(broken);

        assertTrue(verdict.isViolation(),
                "Math.abs(NaN) > tolerance is false, so an unchecked version of this reports a broken "
                        + "output as safely stopped; got " + verdict);
    }

    /**
     * <b>Contract, invariant tier.</b> A run that logs its output early and then stops writing it is
     * still judged on that output — the safe-stop check does not go blind.
     *
     * <p>The worry was that a candidate could drive, stop publishing the output key, and have the last
     * good number stand in as "stopped". It cannot: an RLOG is delta-encoded, so a field that stops being
     * written persists at its last value and the final frame still carries it. Here the output really did
     * go to zero and stay there, so the term holds — and it holds because the log says so, not because
     * nobody looked.
     */
    @Test
    public void anOutputThatStopsBeingLoggedIsStillJudgedOnItsLastValue() throws IOException {
        Path rlog = LoopTestRlogs.write("loop-envelope-stops-logging",
                table -> {
                    table.put(DRIVE_OUTPUT, 0.0);
                    table.put("RealOutputs/Robot/heartbeat", 1.0);
                },
                table -> table.put("RealOutputs/Robot/heartbeat", 2.0));
        RunResult run = RunResult.fromRlog("StopsLogging", rlog, 2);

        assertTrue(EnvelopeGuardrails.safeStopAtEnd(DRIVE_OUTPUT, 1e-6).evaluate(run).holdsCleanly(),
                "the output ended at 0.0 and the delta-encoded log carries that into the final frame");
    }

    /**
     * <b>Contract, invariant tier.</b> The same shape, the other way round: an output still being
     * commanded when the run ends is a violation even if its last write was several frames back.
     */
    @Test
    public void anOutputLeftDrivingIsAViolationEvenIfItStoppedBeingRewritten() throws IOException {
        Path rlog = LoopTestRlogs.write("loop-envelope-left-driving",
                table -> {
                    table.put(DRIVE_OUTPUT, 0.8);
                    table.put("RealOutputs/Robot/heartbeat", 1.0);
                },
                table -> table.put("RealOutputs/Robot/heartbeat", 2.0));
        RunResult run = RunResult.fromRlog("LeftDriving", rlog, 2);

        assertTrue(EnvelopeGuardrails.safeStopAtEnd(DRIVE_OUTPUT, 1e-6).evaluate(run).isViolation(),
                "it was last commanded to 0.8 and never told to stop, so the run ended while driving");
    }

    /**
     * <b>Contract, invariant tier — the honest sort.</b> Every one of {@code scorer-interface.md} §3's
     * seven starter terms is <i>declared</i>, and each one either answers from the run or says UNKNOWN
     * with a reason. Nothing is silently absent and nothing returns a fabricated pass.
     *
     * <p>This is the clause the brief asks for in so many words: sort the seven honestly and let the
     * un-loggable ones say so. An omitted term is invisible; a declared term that says UNKNOWN shows up
     * in every breakdown as a gap somebody chose to leave open.
     */
    @Test
    public void allSevenStarterEnvelopeTermsAreDeclaredAndNoneFakesAPass() throws IOException {
        RunResult run = runLogging("seven-terms", DRIVE_OUTPUT, 0.0);
        List<Guardrail> allSeven = new ArrayList<>();
        // §3, in the doc's own order. Three answer from a log; four cannot, and say why.
        allSeven.add(EnvelopeGuardrails.invariantTestsGreen());
        allSeven.add(EnvelopeGuardrails.withinBound(SLIDE_OUTPUT, -1.0, 1.0));
        allSeven.add(EnvelopeGuardrails.safeStopAtEnd(DRIVE_OUTPUT, 1e-6));
        allSeven.add(EnvelopeGuardrails.noNonFiniteValues());
        allSeven.add(EnvelopeGuardrails.forwardInverseKinematicsRoundTrip());
        allSeven.add(EnvelopeGuardrails.passedInSimFirst());
        allSeven.add(EnvelopeGuardrails.loopTimeUnderBudget(LOOP_TIME, 20.0));

        assertEquals(7, allSeven.size(), "all seven §3 starter terms must be declared, not just the "
                + "checkable ones");
        for (Guardrail guardrail : allSeven) {
            Guardrail.Verdict verdict = guardrail.evaluate(run);
            assertFalse(verdict.reason().trim().isEmpty(),
                    guardrail.name() + " must say why, whatever it concluded");
            if (verdict.isUnknown()) {
                assertEquals(0.0, verdict.penalty(), 0.0,
                        guardrail.name() + ": UNKNOWN must not invent a penalty");
            }
        }
    }

    /**
     * <b>Contract, invariant tier.</b> The three §3 terms that are not log facts <i>at all</i> always say
     * UNKNOWN — never HOLDS — no matter how clean the run is. A perfect run does not make a Gradle exit
     * code or a fact about commit history true.
     *
     * <p>Deliberately does not include {@code loopTimeUnderBudget}: that one <i>is</i> a log fact, and is
     * only unknown when a particular run does not log the key. Folding it in here would claim it is
     * permanently unanswerable, which is a different — and untrue — statement.
     */
    @Test
    public void theTermsThatAreNotLogFactsAtAllSayUnknownEvenForAFlawlessRun() throws IOException {
        RunResult flawless = runLogging("flawless", DRIVE_OUTPUT, 0.0);

        for (Guardrail guardrail : EnvelopeGuardrails.notAnswerableFromALog()) {
            Guardrail.Verdict verdict = guardrail.evaluate(flawless);
            assertEquals(Guardrail.Status.UNKNOWN, verdict.status(),
                    guardrail.name() + " cannot be answered from an RLOG, so a clean run must not "
                            + "promote it to a pass");
        }
    }

    /**
     * <b>Contract, invariant tier.</b> The loop-time budget is a genuine log fact that this run simply
     * does not carry: UNKNOWN because of the run, not because of the term. Given the key, it answers.
     */
    @Test
    public void theLoopTimeBudgetIsUnknownOnlyWhenTheRunDoesNotLogIt() throws IOException {
        RunResult withoutLoopTime = runLogging("no-loop-time", DRIVE_OUTPUT, 0.0);
        RunResult fastEnough = runLogging("fast-loop", LOOP_TIME, 8.0);
        RunResult tooSlow = runLogging("slow-loop", LOOP_TIME, 45.0);
        Guardrail budget = EnvelopeGuardrails.loopTimeUnderBudget(LOOP_TIME, 20.0);

        assertEquals(Guardrail.Status.UNKNOWN, budget.evaluate(withoutLoopTime).status());
        assertTrue(budget.evaluate(fastEnough).holdsCleanly(), "8 ms is inside a 20 ms budget");
        assertTrue(budget.evaluate(tooSlow).isViolation(), "45 ms is not");
    }

    /**
     * <b>Contract, invariant tier.</b> A safe-stop check answers from the run's final frame when it has
     * the key, and says UNKNOWN when it does not — the narrow check §3's term can honestly support.
     */
    @Test
    public void safeStopIsJudgedOnTheFinalFrameOrNotAtAll() throws IOException {
        RunResult stillMoving = runLogging("still-moving", DRIVE_OUTPUT, 0.4);
        RunResult stopped = runLogging("stopped", DRIVE_OUTPUT, 0.0);
        Guardrail safeStop = EnvelopeGuardrails.safeStopAtEnd(DRIVE_OUTPUT, 1e-6);

        assertTrue(safeStop.evaluate(stillMoving).isViolation(),
                "an output still commanded in the final frame is not a safe stop");
        assertTrue(safeStop.evaluate(stopped).holdsCleanly());
        assertEquals(Guardrail.Status.UNKNOWN,
                safeStop.evaluate(runLogging("no-drive", SLIDE_OUTPUT, 0.0)).status(),
                "no logged output means the check did not run, which is not the same as a safe stop");
    }

    /**
     * An inverted bound is rejected where it is declared rather than where it is evaluated, so the
     * mistake surfaces at the scenario that wrote it. Implementation tier.
     */
    @Test
    public void anInvertedBoundIsRejectedAtDeclaration() {
        try {
            EnvelopeGuardrails.withinBound(SLIDE_OUTPUT, 1.0, -1.0);
            fail("an inverted bound must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(SLIDE_OUTPUT));
        }
    }

    /** Writes a one-frame run logging exactly one key, so "this run has no such key" is easy to set up. */
    private static RunResult runLogging(String name, String key, double value) throws IOException {
        Path rlog = LoopTestRlogs.write("loop-envelope-" + name, table -> table.put(key, value));
        return RunResult.fromRlog(name, rlog, 1);
    }
}
