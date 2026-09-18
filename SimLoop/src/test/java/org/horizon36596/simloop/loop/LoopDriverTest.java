package org.horizon36596.simloop.loop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins E3's Contract: last-best is only ever a candidate that passed the gate in full, milestones are
 * event-driven and carry a readable diff, the stop report reproduces before it reports, and the driver
 * proposes nothing.
 *
 * <p>Every RLOG here is named {@code loop-driver-*}, a prefix no other test in the build uses. Two test
 * classes writing one RLOG path is {@code docs/BACKLOG.md} B11, and it fails as a determinism bug that is
 * not one — which, in this class especially, would be a confusing way to spend an afternoon.
 */
public class LoopDriverTest {

    private static final String FINISH_TIME = "RealOutputs/Auto/finishTimeSec";

    // ---------------------------------------------------------------------------------------------
    // Clause 1 — last-best is always a candidate that passed the gate in full
    // ---------------------------------------------------------------------------------------------

    /**
     * Contract: a candidate the gate rejected never becomes last-best, and the previous best is left
     * exactly as it was. Invariant tier.
     *
     * <p>This is §5's promise that whenever a human stops the loop, what they are handed is a fully
     * working solution. Three rejections are offered in a row — a red build, a violated envelope, and an
     * unchecked envelope term — and after all three the answer is still the one that earned it.
     */
    @Test
    public void aRejectedCandidateNeverBecomesLastBestAndLeavesThePreviousOneAlone() throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 0.0);

        LoopDriver.Candidate good = candidate("first good answer", "accepted-1", 30.0,
                Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed());
        assertTrue(driver.offer(good).accepted(), "precondition: the first candidate is accepted");
        assertSame(good, driver.lastBest());

        LoopDriver.Candidate redBuild = candidate("edit with failing tests", "rejected-tests", 1.0,
                Collections.singletonList(holdingGuardrail()),
                Gate.SuppliedFacts.of(Gate.Fact.CONFIRMED_FALSE, Gate.Fact.CONFIRMED_TRUE));
        LoopDriver.Candidate brokenEnvelope = candidate("edit that broke an invariant", "rejected-envelope",
                1.0, Collections.singletonList(violatedGuardrail()), Gate.SuppliedFacts.bothConfirmed());
        LoopDriver.Candidate uncheckedEnvelope = candidate("edit nobody could check", "rejected-unknown",
                1.0, Collections.singletonList(unknownGuardrail()), Gate.SuppliedFacts.bothConfirmed());

        for (LoopDriver.Candidate rejected : Arrays.asList(redBuild, brokenEnvelope, uncheckedEnvelope)) {
            LoopDriver.Decision decision = driver.offer(rejected);
            assertFalse(decision.accepted(), rejected.description() + " must be rejected: "
                    + decision.verdict().explain());
            assertNull(decision.milestone(), "a rejected candidate must not produce a milestone");
            assertSame(good, driver.lastBest(),
                    "last-best must still be the candidate that earned it, after " + rejected.description());
        }
        assertEquals(4, driver.offeredCount());
        assertEquals(1, driver.acceptedCount());
    }

    /**
     * Contract: with nothing accepted yet, the driver says so rather than inventing a last-best. Invariant
     * tier — a stand-in score would be a number nobody measured, and it would be indistinguishable from a
     * real one in every report downstream.
     */
    @Test
    public void withNothingAcceptedTheDriverSaysSoRatherThanInventingAnAnswer() throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 0.0);
        assertFalse(driver.hasLastBest());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, driver::lastBest);
        assertTrue(thrown.getMessage().contains("accepted nothing"),
                "the refusal must say why: " + thrown.getMessage());

        driver.offer(candidate("rejected outright", "nothing-accepted", 12.0,
                Collections.singletonList(violatedGuardrail()), Gate.SuppliedFacts.bothConfirmed()));
        assertFalse(driver.hasLastBest(), "a rejection must not create a last-best out of nothing");
    }

    // ---------------------------------------------------------------------------------------------
    // Clause 2 — milestones are event-driven, and carry a human-readable diff
    // ---------------------------------------------------------------------------------------------

    /**
     * Contract: an accepted candidate that improved by less than the threshold becomes the new best but
     * does not get a milestone. Invariant tier.
     *
     * <p>Both halves matter and they pull in opposite directions, which is why they are pinned together.
     * If every acceptance produced a milestone, a long run would produce hundreds and nobody would read
     * them — and the milestone is the place where gaming is supposed to become visible, so an unread one
     * is a defence that has quietly stopped working. If a below-threshold improvement were also discarded,
     * the loop would refuse progress it had already judged good enough to accept.
     */
    @Test
    public void anImprovementBelowTheThresholdIsKeptButDoesNotGetAMilestone() throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 10.0);

        driver.offer(candidate("first answer", "below-threshold-1", 30.0,
                Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed()));
        assertEquals(1, driver.milestones().size(), "the first acceptance is always a milestone");

        // MINIMIZE, so a smaller finish time is a bigger score: 30.0 -> 29.0 is an improvement of 1.0.
        LoopDriver.Candidate small = candidate("shaved a hair off", "below-threshold-2", 29.0,
                Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed());
        LoopDriver.Decision decision = driver.offer(small);

        assertTrue(decision.accepted(), "a real improvement must still be accepted: "
                + decision.verdict().explain());
        assertSame(small, driver.lastBest(), "it must become the new best");
        assertFalse(decision.emittedMilestone(),
                "an improvement of 1.0 against a threshold of 10.0 must not emit a milestone");
        assertEquals(1, driver.milestones().size());
    }

    /**
     * Contract: an improvement of at least the threshold emits a milestone, and the first acceptance
     * always does. Invariant tier.
     */
    @Test
    public void theFirstAcceptanceAndAnyBigEnoughImprovementEmitMilestones() throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 10.0);

        LoopDriver.Decision first = driver.offer(candidate("first answer", "big-1", 40.0,
                Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed()));
        assertTrue(first.emittedMilestone());
        assertEquals(Collections.singletonList(Milestone.Reason.FIRST_ACCEPTED_CANDIDATE),
                first.milestone().reasons());
        assertNull(first.milestone().previousBest(), "there was nothing before the first one");

        LoopDriver.Decision second = driver.offer(candidate("cut a whole lap out", "big-2", 20.0,
                Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed()));
        assertTrue(second.emittedMilestone(), "an improvement of 20.0 must clear a threshold of 10.0");
        assertTrue(second.milestone().reasons().contains(Milestone.Reason.IMPROVED_BY_AT_LEAST_THE_THRESHOLD));
        assertEquals(2, second.milestone().number(), "milestones are numbered in emission order");
        assertEquals(20.0, second.milestone().improvement(), 1e-9);
        assertNotNull(second.milestone().previousBest());
    }

    /**
     * Contract: a guardrail that is checked and holding now, and was not before, emits a milestone even
     * when the score barely moved. Invariant tier — §5's "qualitatively new working capability".
     *
     * <p>The score moves by 0.5 against a threshold of 10.0, so the improvement alone would not qualify.
     * What qualifies is that a term which was over its soft budget is now inside it: the loop's answer is
     * not merely a bit better, it stopped doing something it was being penalised for.
     */
    @Test
    public void aGuardrailThatIsNowHoldingEmitsAMilestoneEvenWhenTheScoreBarelyMoved() throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 10.0);

        driver.offer(candidate("first answer, a bit over budget", "newly-holding-1", 30.0,
                Collections.singletonList(overBudgetGuardrail(0.5)), Gate.SuppliedFacts.bothConfirmed()));

        LoopDriver.Decision decision = driver.offer(candidate("brought the loop period back in budget",
                "newly-holding-2", 30.0, Collections.singletonList(holdingGuardrail("stayedInBudget")),
                Gate.SuppliedFacts.bothConfirmed()));

        assertTrue(decision.accepted());
        assertTrue(decision.emittedMilestone(),
                "dropping a soft-budget penalty is a new capability, not just a better number");
        assertEquals(0.5, decision.milestone().improvement(), 1e-9,
                "precondition: the score moved by far less than the threshold");
        assertFalse(decision.milestone().reasons().contains(Milestone.Reason.IMPROVED_BY_AT_LEAST_THE_THRESHOLD),
                "the improvement alone must not be what fired it: " + decision.milestone().reasons());
        assertTrue(decision.milestone().reasons()
                        .contains(Milestone.Reason.A_GUARDRAIL_IS_NOW_CHECKED_AND_HOLDING),
                "the reason must be the newly-holding guardrail: " + decision.milestone().reasons());
        assertTrue(decision.milestone().newlyHolding().contains("guardrail stayedInBudget"),
                "the milestone must name it: " + decision.milestone().newlyHolding());
    }

    /**
     * Contract: a milestone carries a human-readable diff — both breakdowns and the candidate's own
     * account of what it changed. Invariant tier.
     *
     * <p>§7's point, pinned: every reward hack improves the number, so a milestone showing only the number
     * shows nothing that could distinguish one. What makes a hack visible is seeing which term moved next
     * to what the candidate said it did.
     */
    @Test
    public void aMilestoneShowsBothBreakdownsAndWhatTheCandidateSaysItChanged() throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 1.0);
        driver.offer(candidate("baseline", "diff-1", 40.0,
                Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed()));
        LoopDriver.Decision decision = driver.offer(
                candidate("raised the flywheel target by 200 rpm", "diff-2", 20.0,
                        Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed()));

        String text = decision.milestone().text();

        assertTrue(text.contains("raised the flywheel target by 200 rpm"),
                "the candidate's own words must be in the milestone: " + text);
        assertTrue(text.contains("previous best:") && text.contains("new best:"),
                "both sides of the diff must be present: " + text);
        assertTrue(text.contains("stayedInsideTheEnvelope"),
                "the guardrail terms must be visible, not just the total: " + text);
        assertTrue(text.contains("TOTAL"), "each side must show its full breakdown: " + text);
        assertFalse(text.contains("\r"),
                "the milestone must read the same on every platform (R5): " + text);
    }

    // ---------------------------------------------------------------------------------------------
    // Clause 3 — the stop report reproduces before it claims
    // ---------------------------------------------------------------------------------------------

    /**
     * Contract: the stop report replays the best candidate's scenario and only then reports its score.
     * Invariant tier.
     */
    @Test
    public void theStopReportReplaysTheBestCandidateBeforeReportingItsScore() throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 0.0);
        LoopDriver.Candidate best = candidate("the answer", "reproduce-ok", 20.0,
                Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed());
        driver.offer(best);

        List<String> replayed = new ArrayList<>();
        StopReport report = driver.stopReport(run -> {
            replayed.add(run.opModeName());
            return rlog("loop-driver-reproduce-ok-replay", 20.0);
        });

        assertEquals(Collections.singletonList("reproduce-ok"), replayed,
                "the report must actually run the scenario again, not assume it would match");
        assertEquals(StopReport.Reproduction.REPRODUCED, report.reproduction());
        assertTrue(report.hasReportableScore());
        assertEquals(best.score().total(), report.reportedScore().total(), 1e-9);
        assertTrue(report.mismatches().isEmpty());
        assertTrue(report.text().contains("REPRODUCED"), report.text());
    }

    /**
     * Contract: when the second run differs, the report is a determinism failure and the score is not
     * handed out. Invariant tier — this is the clause the whole batch turns on, and the one the mutation
     * check targets.
     *
     * <p>Area E's goal sentence is that the engine never reports an improvement it cannot reproduce. A
     * score the second run did not agree with is not a weaker result, it is not a result: the first run
     * may simply have been the lucky one, and every decision made downstream would rest on a number
     * nobody can get back.
     */
    @Test
    public void aBestCandidateThatDoesNotReproduceIsReportedAsADeterminismFailureAndItsScoreIsWithheld()
            throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 0.0);
        driver.offer(candidate("looked good once", "reproduce-bad", 20.0,
                Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed()));

        // The second run of the same scenario comes back with a different number — exactly the
        // non-determinism this check exists to catch.
        StopReport report = driver.stopReport(run -> rlog("loop-driver-reproduce-bad-replay", 21.0));

        assertEquals(StopReport.Reproduction.DID_NOT_REPRODUCE, report.reproduction());
        assertFalse(report.hasReportableScore());
        assertFalse(report.mismatches().isEmpty(), "the report must name what differed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, report::reportedScore);
        assertTrue(thrown.getMessage().contains("not a result"),
                "the refusal must say why the score is withheld: " + thrown.getMessage());
        assertTrue(report.text().contains("DETERMINISM FAILURE"), report.text());
        assertNotNull(report.unreproducedScoreForDiagnosis(),
                "the number that failed to come back is still available for diagnosis");
    }

    /** Contract: with nothing accepted, the report says so and nothing is replayed. */
    @Test
    public void aStopReportWithNoLastBestSaysSoAndReplaysNothing() throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 0.0);

        List<String> replayed = new ArrayList<>();
        StopReport report = driver.stopReport(run -> {
            replayed.add(run.opModeName());
            return rlog("loop-driver-never-called", 1.0);
        });

        assertTrue(replayed.isEmpty(), "there was nothing to replay");
        assertEquals(StopReport.Reproduction.NOT_ATTEMPTED, report.reproduction());
        assertFalse(report.hasReportableScore());
        assertThrows(IllegalStateException.class, report::reportedScore);
        assertTrue(report.text().contains("NO LAST-BEST"), report.text());
    }

    /**
     * Contract: a replayer that writes over the original run is refused. Implementation tier.
     *
     * <p>Comparing a file with itself always passes, so this would report a reproduction that never
     * happened — the one outcome this whole clause exists to prevent, arrived at by accident. It is also
     * {@code docs/BACKLOG.md} B11, two writers on one RLOG, which has already cost this repo two
     * debugging sessions under the wrong diagnosis.
     */
    @Test
    public void aReplayerThatWritesOverTheOriginalRunIsRefused() throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 0.0);
        driver.offer(candidate("the answer", "same-path", 20.0,
                Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed()));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> driver.stopReport(RunResult::rlogPath));
        assertTrue(thrown.getMessage().contains("same file"),
                "the refusal must say what went wrong: " + thrown.getMessage());

        assertThrows(IllegalArgumentException.class, () -> driver.stopReport(null));
    }

    /**
     * Contract: a candidate that quietly drops a guardrail the last-best carried never becomes the new
     * best. Invariant tier.
     *
     * <p>The gate is what refuses it, and {@code GateTest} pins that. This pins the consequence the loop
     * actually depends on: last-best still points at the honest answer afterwards. A loop that let a
     * shrinking envelope through would replace a working answer with one that is only better because it
     * stopped being measured, and every later candidate would be judged against that.
     */
    @Test
    public void aCandidateThatDroppedAGuardrailDoesNotReplaceTheLastBest() throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 0.0);

        LoopDriver.Candidate honest = candidate("measured against both terms", "dropped-honest", 30.0,
                Arrays.asList(holdingGuardrail(), overBudgetGuardrail(2.0)), Gate.SuppliedFacts.bothConfirmed());
        assertTrue(driver.offer(honest).accepted(), "precondition: the honest candidate is accepted");

        LoopDriver.Candidate shrunk = candidate("stopped declaring the term that was costing me",
                "dropped-shrunk", 20.0, Collections.singletonList(holdingGuardrail()),
                Gate.SuppliedFacts.bothConfirmed());
        LoopDriver.Decision decision = driver.offer(shrunk);

        assertFalse(decision.accepted(),
                "a shrinking envelope must be rejected: " + decision.verdict().explain());
        assertSame(honest, driver.lastBest(), "the honest answer must still be the answer");
        assertEquals(1, driver.milestones().size(), "a rejected candidate produces no milestone");
    }

    /**
     * Contract: a replayer that hands back a different spelling of the same file is refused.
     * Implementation tier.
     *
     * <p>{@code Path.equals} compares how a path was written down, not what it points at. A relative
     * {@code build/sim/x.rlog} and its absolute form are unequal paths and one file, so an equality check
     * would wave this through into comparing a file with itself — which always passes, and would report a
     * reproduction that never ran.
     */
    @Test
    public void aReplayerThatReturnsAnotherSpellingOfTheSameFileIsRefused() throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 0.0);
        driver.offer(candidate("the answer", "same-file-other-spelling", 20.0,
                Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed()));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> driver.stopReport(run -> run.rlogPath().toAbsolutePath()));
        assertTrue(thrown.getMessage().contains("same file"),
                "the refusal must say what went wrong: " + thrown.getMessage());
    }

    /** Contract: a replayer that returns no RLOG at all is refused rather than treated as a match. */
    @Test
    public void aReplayerThatReturnsNoRlogIsRefused() throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 0.0);
        driver.offer(candidate("the answer", "null-replay", 20.0,
                Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed()));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> driver.stopReport(run -> null));
        assertTrue(thrown.getMessage().contains("null-replay"),
                "the refusal must name the run it could not reproduce: " + thrown.getMessage());
    }

    // ---------------------------------------------------------------------------------------------
    // Clause 4 — the driver proposes nothing
    // ---------------------------------------------------------------------------------------------

    /**
     * Contract: the driver has no way to propose an edit. Invariant tier.
     *
     * <p>Pinned structurally, because "does not propose" is a negative and there is no call that
     * demonstrates its absence. What can be pinned is the driver's whole public surface: judging, asking
     * what the best is, and reporting. If someone later adds a {@code proposeEdit} — or anything else that
     * would put the optimizer inside the referee — this test fails and says so, which is the alarm.
     *
     * <p>The rule it guards is not layering taste. A gate exists to referee an optimizer, and a referee
     * that could decide what it was refereeing would be judging its own work. Proposing the edit is the
     * {@code /run-loop} skill's job, outside this JVM.
     */
    @Test
    public void theDriverHasNoWayToProposeAnEdit() {
        TreeSet<String> actual = new TreeSet<>();
        for (Method method : LoopDriver.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                actual.add(method.getName());
            }
        }

        TreeSet<String> expected = new TreeSet<>(Arrays.asList(
                "offer", "hasLastBest", "lastBest", "milestones",
                "offeredCount", "acceptedCount", "milestoneThreshold", "stopReport"));

        assertEquals(expected, actual,
                "the driver's public surface is judge, remember and report. Anything new here needs a "
                        + "reason that survives the question 'is the optimizer now inside the referee?'");
    }

    // ---------------------------------------------------------------------------------------------
    // Clause 5 — deterministic (R5)
    // ---------------------------------------------------------------------------------------------

    /**
     * Contract: deterministic. Implementation tier.
     *
     * <p>The driver reads no clock at all, which is the strongest form of this — there is no timer to
     * inject and nothing to seed. Two drivers handed the same candidates in the same order produce
     * byte-identical milestones and byte-identical stop reports.
     */
    @Test
    public void twoDriversGivenTheSameCandidatesProduceTheSameReports() throws IOException {
        LoopDriver.Candidate first = candidate("baseline", "determinism-1", 40.0,
                Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed());
        LoopDriver.Candidate second = candidate("a real gain", "determinism-2", 20.0,
                Collections.singletonList(holdingGuardrail()), Gate.SuppliedFacts.bothConfirmed());

        String reportA = runAndReport(first, second, "loop-driver-determinism-replay-a");
        String reportB = runAndReport(first, second, "loop-driver-determinism-replay-b");

        assertEquals(reportA, reportB);
        assertFalse(reportA.contains("\r"),
                "the report must be the same bytes on every platform: " + reportA);
    }

    /** Offers both candidates to a fresh driver and returns every milestone plus the stop report. */
    private static String runAndReport(LoopDriver.Candidate first, LoopDriver.Candidate second,
            String replayName) throws IOException {
        LoopDriver driver = new LoopDriver(new Gate(), 1.0);
        driver.offer(first);
        driver.offer(second);

        StringBuilder out = new StringBuilder();
        for (Milestone milestone : driver.milestones()) {
            out.append(milestone.text());
        }
        out.append(driver.stopReport(run -> rlog(replayName, 20.0)).text());
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static LoopDriver.Candidate candidate(String description, String opModeName,
            double finishTimeSec, List<Guardrail> guardrails, Gate.SuppliedFacts facts) throws IOException {
        Path path = rlog("loop-driver-" + opModeName, finishTimeSec);
        RunResult run = RunResult.fromRlog(opModeName, path, 1);
        Scorer.Score score = new Scorer(objective(), guardrails).score(run);
        return LoopDriver.Candidate.of(description, run, score, facts);
    }

    private static Path rlog(String baseName, double finishTimeSec) throws IOException {
        return LoopTestRlogs.write(baseName, table -> table.put(FINISH_TIME, finishTimeSec));
    }

    private static Objective objective() {
        return Objective.ofFinalValue("auto finish time", "auto", FINISH_TIME,
                Objective.Direction.MINIMIZE, Objective.Budget.unstated());
    }

    private static Guardrail holdingGuardrail() {
        return holdingGuardrail("stayedInsideTheEnvelope");
    }

    private static Guardrail holdingGuardrail(String name) {
        return new Guardrail() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Verdict evaluate(RunResult ignored) {
                return Verdict.holds();
            }
        };
    }

    private static Guardrail overBudgetGuardrail(double penalty) {
        return new Guardrail() {
            @Override
            public String name() {
                return "stayedInBudget";
            }

            @Override
            public Verdict evaluate(RunResult ignored) {
                return Verdict.overBudget(penalty, "the test guardrail is deliberately over its soft budget");
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
}
