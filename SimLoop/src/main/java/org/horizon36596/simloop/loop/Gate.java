package org.horizon36596.simloop.loop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The acceptance gate: the one place that decides whether a candidate run is allowed to become the new
 * last-best (`docs/process/scorer-interface.md` §6).
 *
 * <p><b>What it is for.</b> {@link Scorer} measures; this judges. Keeping those apart is the point of both
 * classes: a score is a number plus a breakdown and says nothing about whether the change should be kept,
 * and a gate makes exactly one decision and shows every clause it used to make it. The loop
 * ({@code LoopDriver}, E3) does the bookkeeping around both.
 *
 * <h2>The four clauses (§6), all of them, every time</h2>
 * <ol>
 *   <li>{@link Clause#TESTS_GREEN} — the build's test run passed. <b>Supplied</b>, see below.</li>
 *   <li>{@link Clause#ENVELOPE_HOLDS} — no guardrail in the candidate's score is violated, and none is
 *       {@link Guardrail.Status#UNKNOWN}.</li>
 *   <li>{@link Clause#IMPROVES_ON_LAST_BEST} — the candidate scores strictly better than the current
 *       last-best, compared only within one scenario and one objective.</li>
 *   <li>{@link Clause#REVIEW_PASSED} — an independent fresh-context review approved the diff (R10).
 *       <b>Supplied</b>, see below.</li>
 * </ol>
 * Every clause is evaluated on every call, even after one has already failed, because the verdict is a
 * report a human reads: "rejected, and here is the state of all four" is useful, and "rejected at clause 1,
 * the rest unknown" makes the next iteration guess.
 *
 * <h2>UNKNOWN is a rejection, never a pass</h2>
 * A clause this gate could not check does not hold. That is the whole reason {@link Guardrail.Status#UNKNOWN}
 * exists as a separate status from {@code HOLDS} — E1 built it so that an envelope term nobody managed to
 * evaluate could not be quietly counted as a term that was fine. The gate is where that pays off: a
 * candidate whose envelope contains one unchecked term is rejected, and the verdict names the term. The
 * alternative is promoting a change on the strength of an invariant that was never tested, which is exactly
 * the failure the guardrail envelope exists to prevent (domain R9).
 *
 * <h2>Two facts do not come from the log, and are never guessed</h2>
 * "Tests green" is the exit code of a Gradle run. "Review passed" is a judgement made by a fresh-context
 * reviewer or a human. Neither is a field in an RLOG, and neither can be recovered from one. So both are
 * {@link SuppliedFacts} — inputs the caller hands in — and they are a tri-state ({@link Fact}), not a
 * boolean, so that "I did not check" stays distinguishable from "I checked and it failed". A gate that
 * inferred either one would be manufacturing the very approval it exists to require.
 *
 * <h2>Determinism (R5)</h2>
 * This class reads no clock of any kind, fake or real. It is a pure function of its three arguments, so the
 * same inputs give the same verdict in any JVM, in any order, with no timer to inject.
 *
 * <p>Season-agnostic core (R7): depends on {@link Scorer.Score} and {@link Guardrail} and nothing else.
 */
public final class Gate {

    /**
     * Creates a gate.
     *
     * <p>A gate holds no state and reads no clock, so one instance can judge any number of candidates and
     * two instances always agree. It is written out rather than left implicit because an implicit
     * constructor is still public API, and this one is the first thing a caller touches.
     */
    public Gate() {
    }

    /** The four §6 clauses, in the order a verdict reports them. */
    public enum Clause {
        /** The build's tests passed. Supplied by the caller — see {@link SuppliedFacts}. */
        TESTS_GREEN,
        /** No guardrail violated, and none left unchecked. Read from the candidate's score. */
        ENVELOPE_HOLDS,
        /** Strictly better than last-best, within one scenario and one objective. */
        IMPROVES_ON_LAST_BEST,
        /** An independent review approved the diff (R10). Supplied by the caller. */
        REVIEW_PASSED
    }

    /**
     * How one clause came out.
     *
     * <p>Only {@link #HOLDS} and {@link #NOT_YET_APPLICABLE} let a candidate through. Both
     * {@link #FAILED} and {@link #UNKNOWN} block it, and they are kept apart because they mean different
     * things to whoever reads the verdict: one is a result, the other is a gap in the evidence.
     */
    public enum Outcome {
        /** Checked, and satisfied. */
        HOLDS,
        /** Checked, and not satisfied. Blocks acceptance. */
        FAILED,
        /**
         * Could not be checked. Blocks acceptance, and is never treated as a pass.
         *
         * <p>Reached when an envelope term came back {@link Guardrail.Status#UNKNOWN}, when a supplied fact
         * was {@link Fact#NOT_CHECKED}, or when two scores are not comparable so no improvement can be
         * measured.
         */
        UNKNOWN,
        /**
         * The clause does not apply yet, and does not block. The only clause that can reach this is
         * {@link Clause#IMPROVES_ON_LAST_BEST}, and only when there is no last-best to improve on.
         *
         * <p>It is a distinct outcome rather than a {@link #HOLDS} because the first candidate did not beat
         * anything, and a verdict that claimed it did would be a small lie that compounds: E3's milestone
         * report prints these clause outcomes, and "improved on last-best" against an empty field is the
         * kind of sentence that later gets quoted as evidence.
         */
        NOT_YET_APPLICABLE
    }

    /**
     * A fact the gate is told rather than measures. Tri-state on purpose: collapsing "not checked" into
     * "false" would make a gate that was never handed a test result indistinguishable from one that was
     * handed a red build, and the loop's whole safety story rests on that distinction.
     */
    public enum Fact {
        /** The caller ran the check and it passed. */
        CONFIRMED_TRUE,
        /** The caller ran the check and it failed. */
        CONFIRMED_FALSE,
        /** The caller did not run the check. Blocks acceptance, as an {@link Outcome#UNKNOWN}. */
        NOT_CHECKED
    }

    /**
     * The two facts that are not in any log: whether the tests passed, and whether an independent review
     * approved the change.
     *
     * <p>There is no default and no no-argument factory. A caller has to say what it knows about both, which
     * is the point — a gate with a silent default for either one would let a loop accept a candidate nobody
     * built and nobody read.
     */
    public static final class SuppliedFacts {

        private final Fact testsGreen;
        private final Fact reviewPassed;

        private SuppliedFacts(Fact testsGreen, Fact reviewPassed) {
            if (testsGreen == null || reviewPassed == null) {
                throw new IllegalArgumentException(
                        "SuppliedFacts needs both facts; use Fact.NOT_CHECKED to say a check was not run");
            }
            this.testsGreen = testsGreen;
            this.reviewPassed = reviewPassed;
        }

        /**
         * States both facts explicitly.
         *
         * @param testsGreen   result of actually running the verify command, not an assumption
         * @param reviewPassed result of an actual independent review, not an assumption
         * @return the pair of facts, for {@link Gate#judge}
         */
        public static SuppliedFacts of(Fact testsGreen, Fact reviewPassed) {
            return new SuppliedFacts(testsGreen, reviewPassed);
        }

        /**
         * Both checks ran and both passed.
         *
         * @return facts with both set to {@link Fact#CONFIRMED_TRUE}
         */
        public static SuppliedFacts bothConfirmed() {
            return new SuppliedFacts(Fact.CONFIRMED_TRUE, Fact.CONFIRMED_TRUE);
        }

        /**
         * Neither check was run. Every verdict built from this rejects, with two UNKNOWN clauses.
         *
         * @return facts with both set to {@link Fact#NOT_CHECKED}
         */
        public static SuppliedFacts nothingChecked() {
            return new SuppliedFacts(Fact.NOT_CHECKED, Fact.NOT_CHECKED);
        }

        /**
         * What the caller said about the verify command.
         *
         * @return the tests-green fact; never null
         */
        public Fact testsGreen() {
            return testsGreen;
        }

        /**
         * What the caller said about the independent review.
         *
         * @return the review-passed fact; never null
         */
        public Fact reviewPassed() {
            return reviewPassed;
        }

        @Override
        public String toString() {
            return "SuppliedFacts{testsGreen=" + testsGreen + ", reviewPassed=" + reviewPassed + "}";
        }
    }

    /**
     * What the caller knows about the current last-best score. Three states, all of them stated out loud.
     *
     * <p>This exists instead of a nullable {@code Scorer.Score} parameter because {@code null} was doing two
     * unrelated jobs: "this is the first candidate of the run, there is genuinely nothing to beat" and "the
     * loop tried to load the stored last-best and could not". The first is a normal state that should not
     * block acceptance; the second is a loop-side defect that absolutely should. Collapsed into one
     * {@code null}, the defect reports as a clean first-ever run, and a loop that had silently lost its
     * history would look like a loop that was just getting started — for as many iterations as it took
     * someone to notice.
     *
     * <p>It is the same discipline {@link SuppliedFacts} applies to the other two unmeasurable facts: the
     * caller says which case it is in, and there is no default that guesses.
     */
    public static final class LastBest {

        private final Scorer.Score score;
        private final String unavailableReason;

        private LastBest(Scorer.Score score, String unavailableReason) {
            this.score = score;
            this.unavailableReason = unavailableReason;
        }

        /**
         * There is a last-best, and here it is.
         *
         * @param score the score to beat. Required — for "there is none", use {@link #firstCandidateOfRun()}.
         * @return a last-best holding that score
         */
        public static LastBest of(Scorer.Score score) {
            if (score == null) {
                throw new IllegalArgumentException(
                        "LastBest.of needs a score; use firstCandidateOfRun() or couldNotBeDetermined(reason)");
            }
            return new LastBest(score, null);
        }

        /**
         * No candidate has been accepted yet, so there is genuinely nothing to improve on. The improvement
         * clause comes back {@link Outcome#NOT_YET_APPLICABLE} and does not block.
         *
         * @return a last-best meaning "there is genuinely none yet"
         */
        public static LastBest firstCandidateOfRun() {
            return new LastBest(null, null);
        }

        /**
         * The caller should have had a last-best and could not produce one — an unreadable store, a failed
         * deserialize, a lost file. The improvement clause comes back {@link Outcome#UNKNOWN} and blocks,
         * and the verdict quotes the reason.
         *
         * @param reason what went wrong, in words, for the verdict a human reads
         * @return a last-best meaning "this should have existed and does not"
         */
        public static LastBest couldNotBeDetermined(String reason) {
            if (reason == null || reason.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "couldNotBeDetermined needs a reason; an unexplained gap in the evidence is the thing "
                                + "this whole class exists to refuse");
            }
            return new LastBest(null, reason);
        }

        /**
         * The score to beat.
         *
         * @return the score, or null when {@link #isFirstCandidateOfRun()} or {@link #isUnavailable()}
         */
        public Scorer.Score score() {
            return score;
        }

        /**
         * Whether this is the first candidate of a run.
         *
         * @return true when there is genuinely no last-best yet
         */
        public boolean isFirstCandidateOfRun() {
            return score == null && unavailableReason == null;
        }

        /**
         * Whether a last-best was expected and could not be produced.
         *
         * @return true when it was expected and missing, which blocks acceptance
         */
        public boolean isUnavailable() {
            return unavailableReason != null;
        }

        /**
         * Why the last-best could not be produced.
         *
         * @return the reason in words, or null when a last-best was produced
         */
        public String unavailableReason() {
            return unavailableReason;
        }

        @Override
        public String toString() {
            if (isUnavailable()) {
                return "LastBest{unavailable: " + unavailableReason + "}";
            }
            if (isFirstCandidateOfRun()) {
                return "LastBest{none yet}";
            }
            return "LastBest{" + score.total() + "}";
        }
    }

    /** One clause's outcome plus the sentence explaining it, for the verdict a human reads. */
    public static final class ClauseResult {

        private final Clause clause;
        private final Outcome outcome;
        private final String detail;

        ClauseResult(Clause clause, Outcome outcome, String detail) {
            this.clause = clause;
            this.outcome = outcome;
            this.detail = detail;
        }

        /**
         * Which of the four clauses this result is for.
         *
         * @return the clause; never null
         */
        public Clause clause() {
            return clause;
        }

        /**
         * How the clause came out.
         *
         * @return the outcome; never null
         */
        public Outcome outcome() {
            return outcome;
        }

        /**
         * Why this clause came out the way it did, in words, naming the specific term or number.
         *
         * @return the explanation; never null
         */
        public String detail() {
            return detail;
        }

        /**
         * Whether this clause lets the candidate through. Only HOLDS and NOT_YET_APPLICABLE do.
         *
         * @return true when this clause does not block acceptance
         */
        public boolean allowsAcceptance() {
            return outcome == Outcome.HOLDS || outcome == Outcome.NOT_YET_APPLICABLE;
        }

        @Override
        public String toString() {
            return clause + "=" + outcome + "(" + detail + ")";
        }
    }

    /**
     * The gate's answer: accepted or not, plus every clause and why. Never a bare boolean — a decision with
     * no breakdown is a decision nobody can argue with, which is the same rule E1 applied to
     * {@link Scorer.Score#breakdown()}.
     */
    public static final class Verdict {

        private final boolean accepted;
        private final Map<Clause, ClauseResult> clauses;
        private final List<Clause> blockedBy;

        Verdict(Map<Clause, ClauseResult> clauses) {
            this.clauses = Collections.unmodifiableMap(new LinkedHashMap<>(clauses));
            List<Clause> blocked = new ArrayList<>();
            for (ClauseResult result : this.clauses.values()) {
                if (!result.allowsAcceptance()) {
                    blocked.add(result.clause());
                }
            }
            this.blockedBy = Collections.unmodifiableList(blocked);
            this.accepted = blocked.isEmpty();
        }

        /**
         * Whether the candidate was accepted.
         *
         * @return true only when every clause allows acceptance
         */
        public boolean accepted() {
            return accepted;
        }

        /**
         * Every clause that was checked, in {@link Clause} order. Always all four.
         *
         * @return an unmodifiable map from clause to its result
         */
        public Map<Clause, ClauseResult> clauses() {
            return clauses;
        }

        /**
         * One clause's result.
         *
         * @param clause which clause to read
         * @return that clause's result; never null, because all four are always present
         */
        public ClauseResult clause(Clause clause) {
            return clauses.get(clause);
        }

        /**
         * The clauses that blocked acceptance, in {@link Clause} order.
         *
         * @return an unmodifiable list, empty when {@link #accepted()}
         */
        public List<Clause> blockedBy() {
            return blockedBy;
        }

        /**
         * The verdict as a block of text, one line per clause, for a milestone report or a console.
         * Mirrors the shape of {@link Scorer.Score#breakdown()} so the two read as one document when E3
         * prints them together.
         *
         * @return the multi-line report, ending in a newline
         */
        public String explain() {
            StringBuilder out = new StringBuilder();
            out.append(accepted ? "ACCEPTED" : "REJECTED").append('\n');
            for (ClauseResult result : clauses.values()) {
                out.append(String.format(Locale.ROOT, "  %-22s %-18s %s\n",
                        result.clause(), result.outcome(), result.detail()));
            }
            if (!accepted) {
                out.append("  blocked by: ").append(blockedBy).append('\n');
            }
            return out.toString();
        }

        /**
         * The same text as {@link #explain()}.
         *
         * <p>Deliberately not a one-line summary. {@link Scorer.Score#toString()} returns its full
         * {@code breakdown()}, and E3 prints a score and a verdict side by side; if one of them answered
         * {@code println} with a paragraph and the other with a single word, the milestone report would
         * quietly lose half the reasoning depending on which object a caller happened to pass.
         */
        @Override
        public String toString() {
            return explain();
        }
    }

    /**
     * Judge one candidate.
     *
     * @param candidate the candidate run's score. Required.
     * @param lastBest  what the caller knows about the score to beat. Required, and never null: say which
     *                  of {@link LastBest}'s three states applies. The first candidate of a run has nothing
     *                  to improve on and must earn acceptance on the other three clauses; a last-best that
     *                  could not be loaded is a gap in the evidence and blocks.
     * @param facts     the two things the gate cannot read from a log. Required.
     * @return a verdict naming all four clauses
     */
    public Verdict judge(Scorer.Score candidate, LastBest lastBest, SuppliedFacts facts) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate score is required");
        }
        if (lastBest == null) {
            throw new IllegalArgumentException(
                    "LastBest is required; use LastBest.firstCandidateOfRun() when there is genuinely none");
        }
        if (facts == null) {
            throw new IllegalArgumentException(
                    "SuppliedFacts is required; the gate never infers tests-green or review-passed");
        }

        Map<Clause, ClauseResult> results = new LinkedHashMap<>();
        results.put(Clause.TESTS_GREEN, fromSuppliedFact(Clause.TESTS_GREEN, facts.testsGreen(),
                "the verify command passed", "the verify command failed",
                "the verify command was not run for this candidate"));
        results.put(Clause.ENVELOPE_HOLDS, envelopeClause(candidate, lastBest));
        results.put(Clause.IMPROVES_ON_LAST_BEST, improvementClause(candidate, lastBest));
        results.put(Clause.REVIEW_PASSED, fromSuppliedFact(Clause.REVIEW_PASSED, facts.reviewPassed(),
                "an independent review approved the change", "an independent review rejected the change",
                "no independent review was run for this candidate"));
        return new Verdict(results);
    }

    private static ClauseResult fromSuppliedFact(Clause clause, Fact fact,
                                                 String whenTrue, String whenFalse, String whenNotChecked) {
        switch (fact) {
            case CONFIRMED_TRUE:
                return new ClauseResult(clause, Outcome.HOLDS, whenTrue);
            case CONFIRMED_FALSE:
                return new ClauseResult(clause, Outcome.FAILED, whenFalse);
            case NOT_CHECKED:
            default:
                return new ClauseResult(clause, Outcome.UNKNOWN, whenNotChecked);
        }
    }

    /**
     * Envelope clause. A violation fails; an unchecked term is UNKNOWN and therefore also blocks; only a
     * fully-checked, fully-holding envelope passes.
     *
     * <p>Violation is reported ahead of unknown when both are present, because a broken invariant is a
     * harder fact than a missing one and is what the reader should act on first.
     *
     * <p><b>An envelope that lost a guardrail FAILS.</b> If the last-best's score carried a guardrail and
     * the candidate's does not carry it at all, the candidate deleted an envelope term rather than
     * satisfying it, and the clause fails naming the missing terms. This is the empty-envelope hole below
     * taken one term at a time, and it is the more dangerous shape of it: an envelope that goes from seven
     * terms to six still reports six terms checked and holding, still reads as a healthy envelope, and
     * scores higher precisely because the term that was costing penalty is gone. Nothing inside a single
     * score can reveal that — only a comparison with what the envelope used to contain can, which is why
     * the check lives here, where both scores are in hand.
     *
     * <p>Only checked when the two scores are comparable. A different scenario or objective is entitled to
     * a different envelope, and the improvement clause already refuses to compare those.
     *
     * <p><b>A guardrail's identity includes its thresholds, so re-tuning one reads as a replacement.</b>
     * {@code EnvelopeGuardrails} puts the numbers in the guardrail's name ({@code withinBound(Slide/PosIn,
     * 0.0, 40.0)}), which is what stops a candidate swapping a 40-inch limit for a 40,000-inch one and still
     * matching by name. The cost is that a human who legitimately TIGHTENS a bound mid-run, same key and a
     * new number, also produces a name the last-best does not carry, and this clause fails. That is the
     * deliberate side to err on: a false "the envelope changed" stops the loop and is fixed by re-baselining,
     * where a missed one lets a loosened guardrail pass as the same envelope for the rest of the run. The
     * verdict below says both readings out loud rather than only the accusing one.
     *
     * <p><b>An empty envelope is UNKNOWN, not a pass.</b> A score that declares no guardrails at all has
     * had nothing checked, and "nothing was checked" is the definition of {@link Outcome#UNKNOWN}
     * everywhere else in this class. Reading it as {@code HOLDS} would hand the loop the cheapest possible
     * reward hack — drop every guardrail and the envelope clause stops objecting
     * ({@code docs/process/scorer-interface.md} §7). The guardrail set is declared with the objective, so
     * an empty one is either a wiring mistake or a candidate editing its own exam; both should stop the
     * gate, and both are named in the verdict.
     */
    private static ClauseResult envelopeClause(Scorer.Score candidate, LastBest knownLastBest) {
        if (candidate.hasViolation()) {
            return new ClauseResult(Clause.ENVELOPE_HOLDS, Outcome.FAILED,
                    "guardrail(s) violated: " + candidate.violations());
        }
        List<String> dropped = guardrailsDroppedSinceLastBest(candidate, knownLastBest);
        if (!dropped.isEmpty()) {
            return new ClauseResult(Clause.ENVELOPE_HOLDS, Outcome.FAILED,
                    "the envelope no longer carries guardrail(s) the last-best did: " + dropped
                            + ". Either the term was deleted -- deleting a term is not satisfying it, and the "
                            + "score rises either way -- or its threshold was re-tuned, since a guardrail's "
                            + "identity includes its numbers. A deliberate re-tune is fixed by re-baselining "
                            + "the last-best; a deletion is the thing this clause exists to catch.");
        }
        if (candidate.unknownCount() > 0) {
            return new ClauseResult(Clause.ENVELOPE_HOLDS, Outcome.UNKNOWN,
                    "guardrail(s) could not be checked, so the envelope is guarding less than it looks like: "
                            + candidate.unknowns());
        }
        if (candidate.verdicts().isEmpty()) {
            return new ClauseResult(Clause.ENVELOPE_HOLDS, Outcome.UNKNOWN,
                    "this score declares no guardrails at all, so nothing about the envelope was checked");
        }
        return new ClauseResult(Clause.ENVELOPE_HOLDS, Outcome.HOLDS,
                "all " + candidate.verdicts().size() + " guardrail(s) checked and holding");
    }

    /**
     * Guardrails the last-best's score carried and the candidate's does not carry at all, in the order the
     * last-best declared them.
     *
     * <p>Empty when there is no last-best, when it could not be determined, or when the two scores are not
     * comparable — a different scenario or objective is entitled to a different envelope, and comparing
     * those sets would accuse every legitimate scenario change of cheating.
     */
    private static List<String> guardrailsDroppedSinceLastBest(Scorer.Score candidate,
                                                               LastBest knownLastBest) {
        if (knownLastBest.isFirstCandidateOfRun() || knownLastBest.isUnavailable()) {
            return Collections.emptyList();
        }
        Scorer.Score lastBest = knownLastBest.score();
        if (!candidate.isComparableTo(lastBest)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> dropped = new LinkedHashSet<>(lastBest.verdicts().keySet());
        dropped.removeAll(candidate.verdicts().keySet());
        return new ArrayList<>(dropped);
    }

    /**
     * Improvement clause, compared strictly within one scenario and one objective.
     *
     * <p>Two scores from different scenarios or different objectives are not comparable, and this returns
     * {@link Outcome#UNKNOWN} rather than throwing. The reasoning: a mismatch is a caller mistake, but a
     * long autonomous run should reject the candidate and keep going with a verdict that names the mismatch,
     * rather than die. Every such candidate is rejected and every verdict says why, so the mistake is loud
     * in the milestone report without being fatal.
     *
     * <p>A last-best the caller could not produce is {@link Outcome#UNKNOWN} and blocks, checked first.
     * See {@link LastBest} for why that is kept apart from there being no last-best at all.
     *
     * <p>A tie is not an improvement. Equal scores leave last-best standing, so the loop cannot churn
     * through equivalent rewrites and call each one progress.
     *
     * <p>A NaN on either side is {@link Outcome#UNKNOWN}, checked before the comparison. Every comparison
     * against NaN is false in Java, so without that check a NaN candidate would fall through to
     * {@code FAILED} and be reported as an ordinary non-improving run — filing a broken measurement under
     * "tried it, did not help", which is how a real defect hides for a hundred iterations.
     */
    private static ClauseResult improvementClause(Scorer.Score candidate, LastBest knownLastBest) {
        if (knownLastBest.isUnavailable()) {
            return new ClauseResult(Clause.IMPROVES_ON_LAST_BEST, Outcome.UNKNOWN,
                    "the last-best score could not be determined, so no improvement could be measured: "
                            + knownLastBest.unavailableReason());
        }
        if (knownLastBest.isFirstCandidateOfRun()) {
            return new ClauseResult(Clause.IMPROVES_ON_LAST_BEST, Outcome.NOT_YET_APPLICABLE,
                    "no last-best yet; this candidate is judged on the other three clauses alone");
        }
        Scorer.Score lastBest = knownLastBest.score();
        if (!candidate.isComparableTo(lastBest)) {
            return new ClauseResult(Clause.IMPROVES_ON_LAST_BEST, Outcome.UNKNOWN,
                    "not comparable: candidate is " + candidate.objectiveName() + " on "
                            + candidate.scenarioName() + ", last-best is " + lastBest.objectiveName()
                            + " on " + lastBest.scenarioName());
        }
        if (Double.isNaN(candidate.total()) || Double.isNaN(lastBest.total())) {
            return new ClauseResult(Clause.IMPROVES_ON_LAST_BEST, Outcome.UNKNOWN,
                    String.format(Locale.ROOT,
                            "not measurable: candidate total is %s and last-best total is %s; "
                                    + "a NaN score is a broken measurement, not a worse run",
                            candidate.total(), lastBest.total()));
        }
        if (candidate.total() > lastBest.total()) {
            return new ClauseResult(Clause.IMPROVES_ON_LAST_BEST, Outcome.HOLDS,
                    String.format(Locale.ROOT, "%.6f beats %.6f (bigger is better)",
                            candidate.total(), lastBest.total()));
        }
        return new ClauseResult(Clause.IMPROVES_ON_LAST_BEST, Outcome.FAILED,
                String.format(Locale.ROOT,
                        "%.6f does not beat %.6f (bigger is better; a tie is not an improvement)",
                        candidate.total(), lastBest.total()));
    }
}
