package org.horizon36596.simloop.loop;

import org.horizon36596.simloop.sim.RlogDecodedCompare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The bookkeeping half of the loop: holds the last-best, judges each candidate with {@link Gate}, emits
 * a {@link Milestone} when the answer gets qualitatively better, and writes the {@link StopReport}
 * ({@code docs/process/scorer-interface.md} §5, §6, §8).
 *
 * <h2>What this does not do, and why that is the design</h2>
 * <b>This driver proposes nothing.</b> It never edits code, never picks what to try next, never runs
 * Gradle and never decides what the objective should be. Candidates are handed to it; it judges them and
 * remembers the outcome. Proposing the edit is the {@code /run-loop} skill's job — a Claude activity that
 * happens outside this JVM.
 *
 * <p>That split is the safety story, not a layering preference. The gate exists to referee an optimizer.
 * An optimizer that lived inside the referee would be judging its own work, and the one thing a referee
 * must not be able to do is decide what it is refereeing. It also makes the driver a plain function of
 * its inputs, so all of this is testable without generating a single edit.
 *
 * <h2>Last-best is always a fully working answer</h2>
 * The loop can be stopped at any moment, and §5 promises that whatever is held at that instant is a
 * complete working solution rather than a half-applied edit. So the driver stores a candidate only when
 * {@link Gate.Verdict#accepted()} is true, which by construction means no clause failed and no clause was
 * UNKNOWN. A rejected candidate is discarded whole and the previous best stands untouched.
 *
 * <h2>Determinism (R5)</h2>
 * No clock of any kind, fake or real. Milestones are numbered in emission order, which is the only "when"
 * the loop needs; a timestamp would make two identical runs produce different reports for no gain.
 *
 * <p>Season-agnostic core (R7): touches {@link Gate}, {@link Scorer}, {@link RunResult} and
 * {@link RlogDecodedCompare}, and nothing from {@code teamcode}.
 */
public final class LoopDriver {

    /**
     * One candidate offered to the loop: the run, its score, the two facts the gate cannot measure, and
     * the candidate's own account of what it changed.
     *
     * <p>The description is not decoration. It is half of what makes a milestone readable, and §7 is
     * blunt about why: every reward hack improves the number, so the number alone cannot tell a hack from
     * a real gain. Reading "raised the flywheel target by 200 rpm" beside a score that moved is what lets
     * a human notice when the actual answer is "stopped logging the term that was failing".
     */
    public static final class Candidate {

        private final String description;
        private final RunResult run;
        private final Scorer.Score score;
        private final Gate.SuppliedFacts facts;

        private Candidate(String description, RunResult run, Scorer.Score score, Gate.SuppliedFacts facts) {
            if (description == null || description.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "a candidate must say what it changed; an unexplained edit cannot be reviewed at a "
                                + "milestone, which is where gaming is supposed to become visible");
            }
            if (run == null) {
                throw new IllegalArgumentException(
                        "a candidate must carry its run; the stop report replays it to prove the score");
            }
            if (score == null) {
                throw new IllegalArgumentException("a candidate must carry its score");
            }
            if (facts == null) {
                throw new IllegalArgumentException(
                        "a candidate must carry the two facts the gate cannot measure; see Gate.SuppliedFacts");
            }
            this.description = description;
            this.run = run;
            this.score = score;
            this.facts = facts;
        }

        /**
         * Bundles one candidate run with everything needed to judge it.
         *
         * @param description what this candidate changed, in its own words, for the milestone diff
         * @param run         the finished run, which the stop report will replay to prove the score
         * @param score       what {@link Scorer} made of that run
         * @param facts       tests-green and review-passed, neither of which is in any log
         * @return the candidate, ready to offer to a loop driver
         */
        public static Candidate of(String description, RunResult run, Scorer.Score score,
                Gate.SuppliedFacts facts) {
            return new Candidate(description, run, score, facts);
        }

        /** {@return what this candidate changed, in its own words} */
        public String description() {
            return description;
        }

        /** {@return the finished run this candidate was scored from} */
        public RunResult run() {
            return run;
        }

        /** {@return what the scorer made of that run} */
        public Scorer.Score score() {
            return score;
        }

        /** {@return the two facts no log carries: tests-green and review-passed} */
        public Gate.SuppliedFacts facts() {
            return facts;
        }

        @Override
        public String toString() {
            return "Candidate{" + description + ", total=" + score.total() + "}";
        }
    }

    /** What the loop did with one offered candidate. */
    public static final class Decision {

        private final boolean accepted;
        private final Gate.Verdict verdict;
        private final Milestone milestone;

        Decision(boolean accepted, Gate.Verdict verdict, Milestone milestone) {
            this.accepted = accepted;
            this.verdict = verdict;
            this.milestone = milestone;
        }

        /** {@return true when this candidate became the new last-best} */
        public boolean accepted() {
            return accepted;
        }

        /**
         * The gate's full verdict, accepted or not. Never null.
         *
         * @return the gate's full verdict, accepted or not
         */
        public Gate.Verdict verdict() {
            return verdict;
        }

        /**
         * The milestone this candidate produced, or null. An accepted candidate that improved by less
         * than the threshold and unlocked nothing new is still the new best, but does not get one.
         *
         * @return the milestone, or null when this candidate did not earn one
         */
        public Milestone milestone() {
            return milestone;
        }

        /** {@return true when this decision emitted a milestone} */
        public boolean emittedMilestone() {
            return milestone != null;
        }

        /**
         * A one-line status, deliberately shorter than {@link Gate.Verdict#toString()}.
         *
         * <p>The sibling {@code Verdict} prints its whole report from {@code toString()}, because it is
         * printed beside a {@code Score} in a milestone and the two should read as one document. A
         * {@code Decision} is printed once per offered candidate in a long run, where a multi-line block
         * per iteration buries everything worth seeing. The full reasoning is one call away at
         * {@link #verdict()}, and every milestone and the stop report carry it in full.
         */
        @Override
        public String toString() {
            return (accepted ? "ACCEPTED" : "DISCARDED")
                    + (milestone == null ? "" : " (milestone " + milestone.number() + ")")
                    + " " + verdict.blockedBy();
        }
    }

    private final Gate gate;
    private final double milestoneThreshold;
    private final List<Milestone> milestones = new ArrayList<>();

    private Candidate lastBest;
    private Gate.Verdict lastBestVerdict;
    private int offeredCount;
    private int acceptedCount;

    /**
     * Builds a driver that has accepted nothing yet.
     *
     * @param gate               the acceptance gate every candidate is judged by. Required.
     * @param milestoneThreshold how much the total must improve, in score units, before the improvement
     *                           alone is worth a milestone. Must be finite and non-negative; zero means
     *                           every accepted improvement gets one.
     *                           <p>It is declared here rather than read off the {@link Objective} because
     *                           {@link Objective.Budget} is a sentence a human wrote, not a number — §2
     *                           made it prose on purpose and kept it out of the score. Cadence is a
     *                           property of how closely someone wants to watch this particular run, so
     *                           the loop is where it belongs.
     */
    public LoopDriver(Gate gate, double milestoneThreshold) {
        if (gate == null) {
            throw new IllegalArgumentException("a loop without a gate would accept anything");
        }
        if (!Double.isFinite(milestoneThreshold) || milestoneThreshold < 0.0) {
            throw new IllegalArgumentException(
                    "the milestone threshold must be finite and non-negative, got " + milestoneThreshold);
        }
        this.gate = gate;
        this.milestoneThreshold = milestoneThreshold;
    }

    /**
     * Judge one candidate and, if the gate accepts it, make it the new last-best.
     *
     * <p>A rejected candidate is discarded whole: the previous last-best is not touched, not partially
     * updated, and not annotated. There is no state in this class that a rejected candidate can reach.
     *
     * @param candidate the candidate to judge. Required.
     * @return what happened, including the gate's full verdict either way
     */
    public Decision offer(Candidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("a candidate is required");
        }
        offeredCount++;

        Gate.LastBest toBeat = lastBest == null
                ? Gate.LastBest.firstCandidateOfRun()
                : Gate.LastBest.of(lastBest.score());
        Gate.Verdict verdict = gate.judge(candidate.score(), toBeat, candidate.facts());

        if (!verdict.accepted()) {
            return new Decision(false, verdict, null);
        }

        // Belt and braces for §5's promise. Gate.accepted() is already false when any clause is UNKNOWN,
        // so this cannot fire today; it is here so that a future change to the gate's clause set cannot
        // quietly let an unchecked candidate become the answer a human is handed when the loop stops.
        for (Map.Entry<Gate.Clause, Gate.ClauseResult> entry : verdict.clauses().entrySet()) {
            if (entry.getValue().outcome() == Gate.Outcome.UNKNOWN) {
                throw new IllegalStateException(
                        "the gate accepted a candidate whose " + entry.getKey() + " clause is UNKNOWN. "
                                + "Last-best must always be a fully checked answer; refusing to store this "
                                + "one. Verdict:\n" + verdict.explain());
            }
        }

        Scorer.Score previousBestScore = lastBest == null ? null : lastBest.score();
        Gate.Verdict previousBestVerdict = lastBestVerdict;

        lastBest = candidate;
        lastBestVerdict = verdict;
        acceptedCount++;

        Milestone milestone = maybeEmitMilestone(candidate, previousBestScore, previousBestVerdict, verdict);
        return new Decision(true, verdict, milestone);
    }

    /**
     * Works out whether this acceptance deserves a milestone, and records one if so.
     *
     * @return the milestone, or null when nothing qualitatively new happened
     */
    private Milestone maybeEmitMilestone(Candidate candidate, Scorer.Score previousBestScore,
            Gate.Verdict previousBestVerdict, Gate.Verdict verdict) {
        List<Milestone.Reason> reasons = new ArrayList<>();
        List<String> newlyHoldingClauses = new ArrayList<>();
        List<String> newlyHoldingGuardrails = new ArrayList<>();

        if (previousBestScore == null) {
            reasons.add(Milestone.Reason.FIRST_ACCEPTED_CANDIDATE);
        } else {
            if (candidate.score().total() - previousBestScore.total() >= milestoneThreshold) {
                reasons.add(Milestone.Reason.IMPROVED_BY_AT_LEAST_THE_THRESHOLD);
            }
            for (Gate.Clause clause : Gate.Clause.values()) {
                Gate.Outcome now = verdict.clause(clause).outcome();
                Gate.Outcome before = previousBestVerdict.clause(clause).outcome();
                // NOT_YET_APPLICABLE -> HOLDS is not a new capability and is deliberately not counted.
                // It happens exactly once, on the second candidate ever accepted, when
                // IMPROVES_ON_LAST_BEST stops being vacuous because there is finally something to beat.
                // Counting it would emit a milestone on every second acceptance no matter how small the
                // improvement was -- defeating the threshold, and captioning it "a gate clause now holds"
                // when nothing about the answer changed.
                if (now == Gate.Outcome.HOLDS && before != Gate.Outcome.HOLDS
                        && before != Gate.Outcome.NOT_YET_APPLICABLE) {
                    newlyHoldingClauses.add("clause " + clause);
                }
            }
            for (Map.Entry<String, Guardrail.Verdict> entry : candidate.score().verdicts().entrySet()) {
                if (entry.getValue().status() != Guardrail.Status.HOLDS) {
                    continue;
                }
                Guardrail.Verdict before = previousBestScore.verdicts().get(entry.getKey());
                if (before == null || before.status() != Guardrail.Status.HOLDS) {
                    newlyHoldingGuardrails.add("guardrail " + entry.getKey());
                }
            }
            // Two lists rather than one, so which kind of thing started holding is carried as structure
            // rather than read back out of the labels this method just built. Sniffing a "clause " prefix
            // off a string works right up until someone rewords the label.
            if (!newlyHoldingClauses.isEmpty()) {
                reasons.add(Milestone.Reason.A_GATE_CLAUSE_NOW_HOLDS);
            }
            if (!newlyHoldingGuardrails.isEmpty()) {
                reasons.add(Milestone.Reason.A_GUARDRAIL_IS_NOW_CHECKED_AND_HOLDING);
            }
        }

        if (reasons.isEmpty()) {
            return null;
        }
        List<String> newlyHolding = new ArrayList<>(newlyHoldingClauses);
        newlyHolding.addAll(newlyHoldingGuardrails);
        Milestone milestone = new Milestone(milestones.size() + 1, reasons, previousBestScore,
                candidate.score(), candidate.description(), newlyHolding);
        milestones.add(milestone);
        return milestone;
    }

    /** {@return true once the gate has accepted at least one candidate} */
    public boolean hasLastBest() {
        return lastBest != null;
    }

    /**
     * The best answer so far.
     *
     * @throws IllegalStateException if the gate has not accepted anything. Deliberately not null and
     *                               deliberately not an empty placeholder: "the loop has no answer yet"
     *                               is a state the caller has to handle, and a stand-in score would be
     *                               a number nobody measured.
     *
     * @return the best candidate accepted so far
     */
    public Candidate lastBest() {
        if (lastBest == null) {
            throw new IllegalStateException(
                    "there is no last-best: the gate has accepted nothing yet. Check hasLastBest() first.");
        }
        return lastBest;
    }

    /** {@return every milestone emitted so far, in order} */
    public List<Milestone> milestones() {
        return Collections.unmodifiableList(new ArrayList<>(milestones));
    }

    /** {@return how many candidates have been offered} */
    public int offeredCount() {
        return offeredCount;
    }

    /** {@return how many the gate accepted} */
    public int acceptedCount() {
        return acceptedCount;
    }

    /** {@return the threshold this loop was built with, in score units} */
    public double milestoneThreshold() {
        return milestoneThreshold;
    }

    /**
     * Stop the loop and report — replaying the best candidate's scenario first, and reporting a
     * determinism failure instead of a score if the two runs differ.
     *
     * <p>The replay happens here, at the moment of reporting, rather than at acceptance. The reason is
     * cost: a loop may accept a hundred candidates and only the last one's number is ever acted on, so
     * re-running every acceptance would double the cost of the whole loop to prove ninety-nine results
     * nobody will read. What a human acts on is what gets proved.
     *
     * @param replayer re-runs the accepted candidate's scenario. Required, even when there is no
     *                 last-best — a caller that cannot reproduce its runs should find that out when it
     *                 builds the report, not when the loop happens to have found an answer.
     * @return the report; {@link StopReport#reportedScore()} is available only if it reproduced
     * @throws IllegalArgumentException if {@code replayer} is null
     * @throws IllegalStateException    if the replayer returns no RLOG, or writes the second run over the
     *                                  first
     * @throws IOException              if either RLOG cannot be read back for comparison
     */
    public StopReport stopReport(StopReport.ScenarioReplayer replayer) throws IOException {
        if (replayer == null) {
            throw new IllegalArgumentException(
                    "a replayer is required: this report reproduces the best candidate before it reports "
                            + "a score, and a score the second run did not agree with is not a result");
        }
        if (lastBest == null) {
            return new StopReport(StopReport.Reproduction.NOT_ATTEMPTED, null, null,
                    Collections.<RlogDecodedCompare.Mismatch>emptyList(), milestones,
                    offeredCount, acceptedCount);
        }

        Path original = lastBest.run().rlogPath();
        Path second = replayer.replay(lastBest.run());
        if (second == null) {
            throw new IllegalStateException("the replayer returned no RLOG for " + lastBest.run().opModeName());
        }
        if (isTheSameFile(original, second)) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "the replayer wrote the second run to the same file as the first (%s vs %s). Comparing a "
                            + "file with itself always passes, so this would report a reproduction that never "
                            + "happened -- and two writers on one RLOG is BACKLOG B11 besides.",
                    original, second));
        }

        List<RlogDecodedCompare.Mismatch> mismatches = RlogDecodedCompare.compare(original, second);
        StopReport.Reproduction reproduction = mismatches.isEmpty()
                ? StopReport.Reproduction.REPRODUCED
                : StopReport.Reproduction.DID_NOT_REPRODUCE;
        return new StopReport(reproduction, lastBest.score(), lastBest.description(), mismatches,
                milestones, offeredCount, acceptedCount);
    }

    /**
     * Whether two paths name one file on disk.
     *
     * <p>Not {@code Path.equals}, which compares how a path was <i>spelled</i>. {@code build/sim/a.rlog}
     * and an absolute path to that same file are different {@code Path} objects and the same file, and a
     * replayer that returned the second spelling would walk straight past an equality check into comparing
     * a file with itself — which always passes, and would report a reproduction that never ran. That is
     * the one outcome the whole replay check exists to prevent, so it is worth a filesystem call.
     *
     * <p>{@link java.nio.file.Files#isSameFile} needs both files to exist; if either does not, there is no
     * way they are the same file, and the comparison that follows will raise the real problem.
     */
    private static boolean isTheSameFile(Path original, Path second) {
        if (original.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize())) {
            return true;
        }
        try {
            return Files.exists(original) && Files.exists(second) && Files.isSameFile(original, second);
        } catch (IOException cannotTell) {
            // Could not ask the filesystem. Treat them as different and let the field comparison speak;
            // swallowing this would be worse than a false negative here.
            return false;
        }
    }
}
