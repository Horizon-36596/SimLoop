package org.horizon36596.simloop.loop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins E2's Contract, one test per clause: the verdict names every clause, UNKNOWN blocks, the non-log
 * facts are supplied and never inferred, comparison happens only within one scenario and one objective,
 * the first candidate earns its place, and a rejection leaves last-best alone.
 *
 * <p>Note on the helpers: every score built here carries at least one guardrail that holds. That is not
 * decoration. An envelope with no guardrails in it is {@link Gate.Outcome#UNKNOWN} and blocks — see
 * {@link #anEmptyEnvelopeIsNotAPass()} — so a helper that declared none would have quietly tested the
 * wrong thing in every other test in this file.
 */
public class GateTest {

    private static final String FINISH_TIME = "RealOutputs/Auto/finishTimeSec";

    // ---------------------------------------------------------------------------------------------
    // Clause 1 — a verdict names every clause, never a bare boolean
    // ---------------------------------------------------------------------------------------------

    /**
     * Contract: the gate returns a verdict that names every clause it checked and how each came out.
     * Invariant tier.
     *
     * <p>Pinned on all four being present even when the candidate is accepted outright — the easy bug is a
     * gate that short-circuits on the first failure and reports a partial verdict, which reads as "the
     * other three were fine".
     */
    @Test
    public void everyVerdictNamesAllFourClauses() throws IOException {
        Gate.Verdict accepted = new Gate().judge(
                score("names-accepted", 12.0), Gate.LastBest.firstCandidateOfRun(),
                Gate.SuppliedFacts.bothConfirmed());
        Gate.Verdict rejected = new Gate().judge(
                score("names-rejected", 12.0), Gate.LastBest.firstCandidateOfRun(),
                Gate.SuppliedFacts.of(Gate.Fact.CONFIRMED_FALSE, Gate.Fact.CONFIRMED_FALSE));

        for (Gate.Verdict verdict : Arrays.asList(accepted, rejected)) {
            assertEquals(Gate.Clause.values().length, verdict.clauses().size(),
                    "every clause must appear in the verdict: " + verdict.explain());
            for (Gate.Clause clause : Gate.Clause.values()) {
                Gate.ClauseResult result = verdict.clause(clause);
                assertTrue(result != null && result.outcome() != null,
                        clause + " must have an outcome: " + verdict.explain());
                assertFalse(result.detail() == null || result.detail().trim().isEmpty(),
                        clause + " must say why, not just how: " + verdict.explain());
            }
        }
        assertTrue(rejected.explain().contains("REJECTED"));
        assertTrue(accepted.explain().contains("ACCEPTED"));
    }

    /**
     * Contract: the verdict reports its clauses in {@link Gate.Clause} declaration order. Implementation
     * tier.
     *
     * <p>The order is load-bearing for a human reading a milestone report — tests, then envelope, then
     * improvement, then review, which is the order the work actually happens in. Nothing in {@code judge()}
     * enforces it beyond the order of four {@code put} calls into a LinkedHashMap, so it is pinned here
     * rather than left to survive on the next person's care when they add a fifth clause.
     */
    @Test
    public void theVerdictReportsClausesInDeclarationOrder() throws IOException {
        Gate.Verdict verdict = new Gate().judge(
                score("clause-order", 12.0), Gate.LastBest.firstCandidateOfRun(),
                Gate.SuppliedFacts.bothConfirmed());

        assertEquals(Arrays.asList(Gate.Clause.values()), new ArrayList<>(verdict.clauses().keySet()),
                "clauses must be reported in declaration order: " + verdict.explain());
    }

    /**
     * Contract: every clause is evaluated even after one has already failed, so the verdict is a report
     * rather than a stack trace. Implementation tier.
     *
     * <p>A failing tests-green must not stop the envelope from being checked — otherwise the next
     * iteration has to re-run everything to learn whether its envelope was also broken.
     */
    @Test
    public void aFailedClauseDoesNotStopTheOthersFromBeingChecked() throws IOException {
        Gate.Verdict verdict = new Gate().judge(
                scoreWithViolation("all-clauses-still-checked"), Gate.LastBest.firstCandidateOfRun(),
                Gate.SuppliedFacts.of(Gate.Fact.CONFIRMED_FALSE, Gate.Fact.CONFIRMED_TRUE));

        assertEquals(Gate.Outcome.FAILED, verdict.clause(Gate.Clause.TESTS_GREEN).outcome());
        assertEquals(Gate.Outcome.FAILED, verdict.clause(Gate.Clause.ENVELOPE_HOLDS).outcome(),
                "the envelope must still be evaluated after tests-green failed: " + verdict.explain());
        assertEquals(Gate.Outcome.HOLDS, verdict.clause(Gate.Clause.REVIEW_PASSED).outcome());
        assertEquals(Arrays.asList(Gate.Clause.TESTS_GREEN, Gate.Clause.ENVELOPE_HOLDS),
                verdict.blockedBy());
    }

    // ---------------------------------------------------------------------------------------------
    // Clause 2 — UNKNOWN is never a pass
    // ---------------------------------------------------------------------------------------------

    /**
     * Contract: an UNKNOWN envelope term blocks acceptance. Invariant tier — this is the clause the whole
     * batch turns on.
     *
     * <p>The candidate here is otherwise perfect: tests green, review passed, no last-best to beat, and
     * not one violated guardrail. It is rejected purely because one envelope term could not be evaluated,
     * which is the difference between "the envelope held" and "nobody checked the envelope".
     */
    @Test
    public void anUncheckableGuardrailBlocksAcceptanceRatherThanPassing() throws IOException {
        Scorer.Score candidate = scoreWithUnknown("unknown-blocks");
        assertFalse(candidate.hasViolation(), "precondition: nothing is actually violated");
        assertEquals(1, candidate.unknownCount(), "precondition: exactly one term is unchecked");

        Gate.Verdict verdict = new Gate().judge(candidate, Gate.LastBest.firstCandidateOfRun(),
                Gate.SuppliedFacts.bothConfirmed());

        assertFalse(verdict.accepted(),
                "an unchecked envelope term must block acceptance: " + verdict.explain());
        assertEquals(Gate.Outcome.UNKNOWN, verdict.clause(Gate.Clause.ENVELOPE_HOLDS).outcome());
        assertTrue(verdict.clause(Gate.Clause.ENVELOPE_HOLDS).detail().contains("invariantTestsGreen"),
                "the verdict must name the term nobody checked: " + verdict.explain());
    }

    /**
     * Contract: a violated guardrail fails the envelope clause, and the verdict names it. Invariant tier.
     */
    @Test
    public void aViolatedGuardrailFailsTheEnvelopeClauseAndIsNamed() throws IOException {
        Gate.Verdict verdict = new Gate().judge(
                scoreWithViolation("violated-named"), Gate.LastBest.firstCandidateOfRun(),
                Gate.SuppliedFacts.bothConfirmed());

        assertFalse(verdict.accepted());
        assertEquals(Gate.Outcome.FAILED, verdict.clause(Gate.Clause.ENVELOPE_HOLDS).outcome());
        assertTrue(verdict.clause(Gate.Clause.ENVELOPE_HOLDS).detail().contains("heldTheEnvelope"),
                "the verdict must name the violated term: " + verdict.explain());
    }

    /**
     * Contract: when an envelope holds both a violated term and an unchecked one, the verdict reports the
     * violation. Implementation tier.
     *
     * <p>Both outcomes block, so the accept/reject answer is the same either way and a swapped check order
     * would not fail any other test in this file. What changes is what the human reads first: a broken
     * invariant is a harder fact than a missing one and is the thing to act on. The class javadoc promises
     * this ordering, so it is pinned rather than left as a comment.
     */
    @Test
    public void aViolationIsReportedAheadOfAnUncheckedTerm() throws IOException {
        Scorer.Score candidate = scoreWith("violation-beats-unknown",
                Arrays.asList(violatedGuardrail(), unknownGuardrail()));
        assertTrue(candidate.hasViolation(), "precondition: one term is violated");
        assertEquals(1, candidate.unknownCount(), "precondition: one term is also unchecked");

        Gate.ClauseResult envelope = new Gate()
                .judge(candidate, Gate.LastBest.firstCandidateOfRun(), Gate.SuppliedFacts.bothConfirmed())
                .clause(Gate.Clause.ENVELOPE_HOLDS);

        assertEquals(Gate.Outcome.FAILED, envelope.outcome(),
                "a violation present alongside an unchecked term must be reported as the violation");
        assertTrue(envelope.detail().contains("heldTheEnvelope"),
                "the verdict must name the violated term: " + envelope.detail());
    }

    /**
     * Contract: a score that declares no guardrails at all is UNKNOWN, not a pass. Invariant tier — this is
     * the cheapest reward hack there is.
     *
     * <p>{@code scorer-interface.md} §7 names it directly: a candidate that can make the envelope clause
     * stop objecting by deleting every guardrail has been handed a way to pass the gate without passing
     * anything. "No guardrails were violated" is true of an empty envelope and means nothing, so the gate
     * reports what is actually the case — nothing was checked — and blocks.
     */
    @Test
    public void anEmptyEnvelopeIsNotAPass() throws IOException {
        Scorer.Score candidate = scoreWith("empty-envelope", Collections.<Guardrail>emptyList());
        assertFalse(candidate.hasViolation(), "precondition: with no guardrails, nothing is violated");
        assertEquals(0, candidate.unknownCount(), "precondition: with no guardrails, nothing is unchecked");

        Gate.Verdict verdict = new Gate().judge(candidate, Gate.LastBest.firstCandidateOfRun(),
                Gate.SuppliedFacts.bothConfirmed());

        assertFalse(verdict.accepted(),
                "an envelope with nothing in it must not pass the envelope clause: " + verdict.explain());
        assertEquals(Gate.Outcome.UNKNOWN, verdict.clause(Gate.Clause.ENVELOPE_HOLDS).outcome());
        assertTrue(verdict.clause(Gate.Clause.ENVELOPE_HOLDS).detail().contains("no guardrails"),
                "the verdict must say the envelope was empty, not that it held: " + verdict.explain());
    }

    /**
     * Contract: a candidate whose envelope lost a guardrail the last-best carried fails the envelope
     * clause. Invariant tier — this is the empty-envelope hole one term at a time, and it is the shape
     * that actually survives review.
     *
     * <p>The candidate here scores higher than the last-best, has no violated term and no unchecked term,
     * and its envelope reports every guardrail it declares as holding. It is rejected because one of the
     * two terms the previous best was being judged against is simply not there any more. Deleting the term
     * that was costing penalty is the cheapest way to make the number go up, and from inside a single
     * score it is indistinguishable from having satisfied it.
     */
    @Test
    public void anEnvelopeThatQuietlyLostAGuardrailFailsRatherThanPassing() throws IOException {
        Scorer.Score lastBest = scoreWith("dropped-lastbest",
                Arrays.asList(holdingGuardrail(), overBudgetGuardrail()));
        Scorer.Score candidate = scoreWith("dropped-candidate",
                Collections.singletonList(holdingGuardrail()));
        assertTrue(candidate.total() > lastBest.total(),
                "precondition: dropping the penalised term really does raise the score");
        assertFalse(candidate.hasViolation(), "precondition: nothing the candidate declares is violated");
        assertEquals(0, candidate.unknownCount(), "precondition: nothing the candidate declares is unchecked");

        Gate.Verdict verdict = new Gate().judge(candidate, Gate.LastBest.of(lastBest),
                Gate.SuppliedFacts.bothConfirmed());

        assertFalse(verdict.accepted(),
                "a candidate that deleted an envelope term must not be accepted: " + verdict.explain());
        assertEquals(Gate.Outcome.FAILED, verdict.clause(Gate.Clause.ENVELOPE_HOLDS).outcome());
        assertTrue(verdict.clause(Gate.Clause.ENVELOPE_HOLDS).detail().contains("stayedInBudget"),
                "the verdict must name the term that went missing: " + verdict.explain());
    }

    /**
     * Contract: the dropped-guardrail check does not fire across scenarios. Implementation tier.
     *
     * <p>A different scenario is entitled to a different envelope. Without this, every legitimate change
     * of scenario would be reported as an attempt to cheat, and a check that accuses honest work gets
     * switched off.
     */
    @Test
    public void adifferentScenarioIsAllowedADifferentEnvelope() throws IOException {
        Scorer.Score lastBest = new Scorer(objective("auto"),
                Arrays.asList(holdingGuardrail(), overBudgetGuardrail())).score(run("scenario-lastbest", 30.0));
        Scorer.Score candidate = new Scorer(objective("teleop"),
                Collections.singletonList(holdingGuardrail())).score(run("scenario-candidate", 12.0));

        Gate.ClauseResult envelope = new Gate()
                .judge(candidate, Gate.LastBest.of(lastBest), Gate.SuppliedFacts.bothConfirmed())
                .clause(Gate.Clause.ENVELOPE_HOLDS);

        assertEquals(Gate.Outcome.HOLDS, envelope.outcome(),
                "a different scenario may declare a different envelope: " + envelope.detail());
    }

    // ---------------------------------------------------------------------------------------------
    // Clause 3 — the two non-log facts are supplied, never inferred
    // ---------------------------------------------------------------------------------------------

    /**
     * Contract: "not checked" is distinguishable from "checked and failed", and both block. Invariant
     * tier — collapsing the two would let a gate that was never handed a test result look exactly like one
     * handed a red build, and the loop's safety story rests on telling them apart.
     */
    @Test
    public void anUnrunCheckIsUnknownAndAFailedCheckIsFailedAndBothBlock() throws IOException {
        Gate gate = new Gate();

        Gate.Verdict notRun = gate.judge(score("facts-not-run", 12.0),
                Gate.LastBest.firstCandidateOfRun(), Gate.SuppliedFacts.nothingChecked());
        Gate.Verdict ranAndFailed = gate.judge(score("facts-failed", 12.0),
                Gate.LastBest.firstCandidateOfRun(),
                Gate.SuppliedFacts.of(Gate.Fact.CONFIRMED_FALSE, Gate.Fact.CONFIRMED_FALSE));

        assertEquals(Gate.Outcome.UNKNOWN, notRun.clause(Gate.Clause.TESTS_GREEN).outcome());
        assertEquals(Gate.Outcome.UNKNOWN, notRun.clause(Gate.Clause.REVIEW_PASSED).outcome());
        assertEquals(Gate.Outcome.FAILED, ranAndFailed.clause(Gate.Clause.TESTS_GREEN).outcome());
        assertEquals(Gate.Outcome.FAILED, ranAndFailed.clause(Gate.Clause.REVIEW_PASSED).outcome());
        assertFalse(notRun.accepted(), "an unrun check must block: " + notRun.explain());
        assertFalse(ranAndFailed.accepted(), "a failed check must block: " + ranAndFailed.explain());
    }

    /**
     * Contract: the gate never infers the non-log facts. Invariant tier.
     *
     * <p>Pinned structurally, because "did not infer" is a negative: there is no way to call the gate
     * without supplying both facts. A null {@code SuppliedFacts} is rejected, and {@code SuppliedFacts}
     * itself cannot be built without naming both. If a later refactor adds a convenience overload that
     * defaults either fact, this test stops compiling or the null check fires — which is the alarm.
     */
    @Test
    public void theGateCannotBeCalledWithoutBeingToldTheNonLogFacts() throws IOException {
        Scorer.Score candidate = score("facts-required", 12.0);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new Gate().judge(candidate, Gate.LastBest.firstCandidateOfRun(), null));
        assertTrue(thrown.getMessage().contains("never infers"),
                "the refusal must say why: " + thrown.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> Gate.SuppliedFacts.of(null, Gate.Fact.CONFIRMED_TRUE));
        assertThrows(IllegalArgumentException.class,
                () -> Gate.SuppliedFacts.of(Gate.Fact.CONFIRMED_TRUE, null));
    }

    // ---------------------------------------------------------------------------------------------
    // Clause 4 — comparison only within one scenario and one objective
    // ---------------------------------------------------------------------------------------------

    /**
     * Contract: improvement is only ever compared within one scenario and one objective. Invariant tier.
     *
     * <p>The candidate here scores far higher than the last-best in raw numbers. It is still not accepted,
     * because the two scores measure different scenarios — a gate that compared them would report an
     * improvement nobody made.
     */
    @Test
    public void scoresFromDifferentScenariosAreNotComparedAndTheCandidateIsRejected() throws IOException {
        Scorer.Score candidate = scoreOnScenario("compare-candidate", "teleop", 1.0);
        Scorer.Score lastBest = scoreOnScenario("compare-lastbest", "auto", 30.0);
        assertTrue(candidate.total() > lastBest.total(), "precondition: the raw numbers do favour the candidate");

        Gate.Verdict verdict = new Gate().judge(candidate, Gate.LastBest.of(lastBest),
                Gate.SuppliedFacts.bothConfirmed());

        assertFalse(verdict.accepted(), "incomparable scores must not produce an acceptance: " + verdict.explain());
        assertEquals(Gate.Outcome.UNKNOWN, verdict.clause(Gate.Clause.IMPROVES_ON_LAST_BEST).outcome());
        assertTrue(verdict.clause(Gate.Clause.IMPROVES_ON_LAST_BEST).detail().contains("teleop")
                        && verdict.clause(Gate.Clause.IMPROVES_ON_LAST_BEST).detail().contains("auto"),
                "the verdict must name both scenarios so the mismatch is obvious: " + verdict.explain());
    }

    /**
     * Contract: a tie is not an improvement. Implementation tier — without this the loop can churn through
     * equivalent rewrites and call each one progress.
     */
    @Test
    public void anEqualScoreIsNotAnImprovement() throws IOException {
        Scorer.Score candidate = score("tie-candidate", 12.0);
        Scorer.Score lastBest = score("tie-lastbest", 12.0);
        assertEquals(candidate.total(), lastBest.total(), 1e-12, "precondition: the two really do tie");

        Gate.Verdict verdict = new Gate().judge(candidate, Gate.LastBest.of(lastBest),
                Gate.SuppliedFacts.bothConfirmed());

        assertFalse(verdict.accepted());
        assertEquals(Gate.Outcome.FAILED, verdict.clause(Gate.Clause.IMPROVES_ON_LAST_BEST).outcome());
        assertTrue(verdict.clause(Gate.Clause.IMPROVES_ON_LAST_BEST).detail().contains("tie"),
                "the verdict should say a tie is not an improvement: " + verdict.explain());
    }

    /** Contract: a genuinely better candidate on the same scenario and objective is accepted. */
    @Test
    public void aBetterCandidateOnTheSameScenarioAndObjectiveIsAccepted() throws IOException {
        Scorer.Score candidate = score("better-candidate", 12.0);
        Scorer.Score lastBest = score("better-lastbest", 30.0);

        Gate.Verdict verdict = new Gate().judge(candidate, Gate.LastBest.of(lastBest),
                Gate.SuppliedFacts.bothConfirmed());

        assertTrue(verdict.accepted(), verdict.explain());
        assertEquals(Gate.Outcome.HOLDS, verdict.clause(Gate.Clause.IMPROVES_ON_LAST_BEST).outcome());
        assertTrue(verdict.blockedBy().isEmpty());
    }

    /**
     * Contract: a non-finite measurement never reaches the improvement comparison at all. Invariant tier.
     *
     * <p>Every comparison against NaN is false in Java, so a NaN total would fall through a
     * greater-than into the not-an-improvement branch and be reported as an ordinary worse run — filing a
     * broken measurement under "tried it, did not help".
     *
     * <p><b>Where that is actually prevented is upstream, and this pins it there.</b> E2's review raised
     * the NaN fall-through against the gate; checking it out showed the loop is already closed one layer
     * down and cannot be reached from the gate's side: {@link Objective} refuses a non-finite metric when
     * the score is built, and {@code Guardrail.Verdict.overBudget} refuses a non-finite penalty, so
     * {@code total()} can be negative infinity (a violated envelope, on purpose) but never NaN. The gate
     * keeps its own NaN check as the last line — it takes a bare {@code double} from another class and
     * costs one branch — but the check that does the work is this one, so this is the one under test.
     */
    @Test
    public void aNonFiniteMeasurementIsRefusedWhenTheScoreIsBuiltAndNeverReachesTheGate() throws IOException {
        Objective.NonFiniteMetricException thrown = assertThrows(Objective.NonFiniteMetricException.class,
                () -> score("nan-candidate", Double.NaN));
        assertTrue(thrown.getMessage().contains("NaN"),
                "the refusal must name the value it refused: " + thrown.getMessage());

        Scorer.Score violated = scoreWithViolation("nan-neighbour-violation");
        assertFalse(Double.isNaN(violated.total()),
                "a violated envelope must score negative infinity, not NaN: " + violated.total());
        assertEquals(Double.NEGATIVE_INFINITY, violated.total(), 0.0,
                "an envelope violation is infinitely penalised on purpose");
    }

    /**
     * Contract: a last-best the caller could not produce blocks as UNKNOWN, and is not read as there being
     * no last-best. Invariant tier.
     *
     * <p>These are the two states a single nullable parameter used to share. "The run just started" does
     * not block; "the loop lost its history" must. Told apart, a loop that silently drops its stored best
     * is rejected with the reason quoted; collapsed together, it looks like a clean first iteration and
     * keeps looking like one every time round.
     */
    @Test
    public void aLastBestThatCouldNotBeLoadedBlocksAndIsNotMistakenForTheFirstRun() throws IOException {
        Scorer.Score candidate = score("lastbest-unavailable", 12.0);

        Gate.Verdict verdict = new Gate().judge(candidate,
                Gate.LastBest.couldNotBeDetermined("the stored last-best did not parse"),
                Gate.SuppliedFacts.bothConfirmed());

        assertFalse(verdict.accepted(),
                "a last-best that could not be read must block: " + verdict.explain());
        assertEquals(Gate.Outcome.UNKNOWN, verdict.clause(Gate.Clause.IMPROVES_ON_LAST_BEST).outcome());
        assertTrue(verdict.clause(Gate.Clause.IMPROVES_ON_LAST_BEST).detail().contains("did not parse"),
                "the verdict must quote why it could not be read: " + verdict.explain());

        assertThrows(IllegalArgumentException.class, () -> Gate.LastBest.couldNotBeDetermined("  "),
                "an unexplained gap in the evidence must be refused at construction");
        assertThrows(IllegalArgumentException.class, () -> Gate.LastBest.of(null));
        assertThrows(IllegalArgumentException.class,
                () -> new Gate().judge(candidate, null, Gate.SuppliedFacts.bothConfirmed()));
    }

    // ---------------------------------------------------------------------------------------------
    // Clause 5 — the first candidate earns its place
    // ---------------------------------------------------------------------------------------------

    /**
     * Contract: with no last-best, a candidate must still pass clauses 1, 2 and 4 on its own merits.
     * Invariant tier.
     *
     * <p>Two halves, because the easy bug is a gate that treats "no last-best" as "accept anything":
     * a clean first candidate is accepted, and a first candidate with a broken envelope is not.
     */
    @Test
    public void theFirstCandidateIsAcceptedOnlyIfItEarnsItOnTheOtherThreeClauses() throws IOException {
        Gate gate = new Gate();

        Gate.Verdict clean = gate.judge(score("first-clean", 12.0), Gate.LastBest.firstCandidateOfRun(),
                Gate.SuppliedFacts.bothConfirmed());
        assertTrue(clean.accepted(), "a clean first candidate should be accepted: " + clean.explain());
        assertEquals(Gate.Outcome.NOT_YET_APPLICABLE,
                clean.clause(Gate.Clause.IMPROVES_ON_LAST_BEST).outcome(),
                "the first candidate did not beat anything and the verdict must not claim it did");

        Gate.Verdict broken = gate.judge(scoreWithViolation("first-broken"),
                Gate.LastBest.firstCandidateOfRun(), Gate.SuppliedFacts.bothConfirmed());
        assertFalse(broken.accepted(),
                "no last-best must not mean 'accept anything': " + broken.explain());
        assertEquals(Gate.Outcome.FAILED, broken.clause(Gate.Clause.ENVELOPE_HOLDS).outcome());
    }

    // ---------------------------------------------------------------------------------------------
    // Clause 6 — a rejection leaves last-best untouched
    // ---------------------------------------------------------------------------------------------

    /**
     * Contract: a rejected candidate leaves last-best untouched, and the verdict says which clause
     * rejected it. Invariant tier.
     *
     * <p>Pinned here as the gate's half of the promise: judging is a pure read, so the same last-best
     * object comes back unchanged and re-judging with it gives the same answer. E3 owns the other half —
     * actually holding the last-best — and pins that separately.
     */
    @Test
    public void judgingDoesNotMutateEitherScoreAndTheVerdictNamesTheBlockingClause() throws IOException {
        Scorer.Score lastBest = score("untouched-lastbest", 12.0);
        Scorer.Score candidate = score("untouched-candidate", 30.0);
        double lastBestTotalBefore = lastBest.total();
        double candidateTotalBefore = candidate.total();

        Gate gate = new Gate();
        Gate.LastBest toBeat = Gate.LastBest.of(lastBest);
        Gate.Verdict first = gate.judge(candidate, toBeat, Gate.SuppliedFacts.bothConfirmed());
        Gate.Verdict second = gate.judge(candidate, toBeat, Gate.SuppliedFacts.bothConfirmed());

        assertFalse(first.accepted(), "precondition: the slower candidate is rejected");
        assertEquals(Collections.singletonList(Gate.Clause.IMPROVES_ON_LAST_BEST), first.blockedBy(),
                "the verdict must name the clause that rejected it: " + first.explain());
        assertEquals(lastBestTotalBefore, lastBest.total(), 1e-12, "last-best must be untouched");
        assertEquals(candidateTotalBefore, candidate.total(), 1e-12, "the candidate must be untouched");
        assertEquals(first.blockedBy(), second.blockedBy(), "judging twice must give the same answer");
        assertEquals(first.explain(), second.explain(), "judging twice must give the same report");
    }

    /**
     * Contract: deterministic (R5). Implementation tier.
     *
     * <p>The gate reads no clock at all, which is the strongest form of this: the same three inputs give a
     * byte-identical explanation every time, with no timer to inject and nothing to seed.
     *
     * <p>Also pins that the report carries no carriage returns. {@code explain()} is written into milestone
     * reports that are compared across machines, and a {@code %n} anywhere in it would make the same
     * verdict come out as different bytes on Windows than on the CI runner — a determinism failure that
     * would look like a content difference.
     */
    @Test
    public void theSameInputsAlwaysProduceTheSameExplanation() throws IOException {
        Scorer.Score candidate = score("determinism-candidate", 12.0);
        Scorer.Score lastBest = score("determinism-lastbest", 30.0);

        String first = new Gate()
                .judge(candidate, Gate.LastBest.of(lastBest), Gate.SuppliedFacts.bothConfirmed()).explain();
        String second = new Gate()
                .judge(candidate, Gate.LastBest.of(lastBest), Gate.SuppliedFacts.bothConfirmed()).explain();

        assertEquals(first, second);
        assertFalse(first.contains("\r"),
                "the report must use one line ending on every platform, not the platform's own: " + first);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static Scorer.Score score(String name, double finishTimeSec) throws IOException {
        return scoreOnScenario(name, "auto", finishTimeSec);
    }

    private static Scorer.Score scoreOnScenario(String name, String scenario, double finishTimeSec)
            throws IOException {
        return new Scorer(objective(scenario), Collections.singletonList(holdingGuardrail()))
                .score(run(name, finishTimeSec));
    }

    /** A score on the "auto" scenario carrying exactly the guardrails handed in — including none at all. */
    private static Scorer.Score scoreWith(String name, List<Guardrail> guardrails) throws IOException {
        return new Scorer(objective("auto"), guardrails).score(run(name, 12.0));
    }

    private static Scorer.Score scoreWithViolation(String name) throws IOException {
        return scoreWith(name, Collections.singletonList(violatedGuardrail()));
    }

    private static Scorer.Score scoreWithUnknown(String name) throws IOException {
        return scoreWith(name, Collections.singletonList(unknownGuardrail()));
    }

    /** A guardrail that always holds, so an envelope can be non-empty without being broken. */
    private static Guardrail holdingGuardrail() {
        return new Guardrail() {
            @Override
            public String name() {
                return "stayedInsideTheEnvelope";
            }

            @Override
            public Verdict evaluate(RunResult ignored) {
                return Verdict.holds();
            }
        };
    }

    /** A guardrail that is over its soft budget: penalised, but not an envelope violation. */
    private static Guardrail overBudgetGuardrail() {
        return new Guardrail() {
            @Override
            public String name() {
                return "stayedInBudget";
            }

            @Override
            public Verdict evaluate(RunResult ignored) {
                return Verdict.overBudget(2.0, "the test guardrail is deliberately over its soft budget");
            }
        };
    }

    private static Guardrail violatedGuardrail() {
        return new Guardrail() {
            @Override
            public String name() {
                return "heldTheEnvelope";
            }

            @Override
            public Verdict evaluate(RunResult ignored) {
                return Verdict.violated("the test guardrail is deliberately broken");
            }
        };
    }

    private static Guardrail unknownGuardrail() {
        return new Guardrail() {
            @Override
            public String name() {
                return "invariantTestsGreen";
            }

            @Override
            public Verdict evaluate(RunResult ignored) {
                return Verdict.unknown("a Gradle exit code is not a field in this run's log");
            }
        };
    }

    private static Objective objective(String scenarioName) {
        return Objective.ofFinalValue("auto finish time", scenarioName, FINISH_TIME,
                Objective.Direction.MINIMIZE, Objective.Budget.unstated());
    }

    private static RunResult run(String name, double finishTimeSec) throws IOException {
        Path rlog = LoopTestRlogs.write("loop-gate-" + name, table -> table.put(FINISH_TIME, finishTimeSec));
        return RunResult.fromRlog(name, rlog, 1);
    }
}
