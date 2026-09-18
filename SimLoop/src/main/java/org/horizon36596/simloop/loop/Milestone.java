package org.horizon36596.simloop.loop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One milestone: a point where the loop's best answer got qualitatively better, and the side-by-side
 * evidence for saying so ({@code docs/process/scorer-interface.md} §5).
 *
 * <h2>Event-driven, never on a clock</h2>
 * A milestone is emitted because something happened — the best improved by at least the loop's declared
 * threshold, or a clause or guardrail that was not holding before is holding now. It is never emitted
 * because an interval elapsed. That is not a style preference: a time-sliced milestone stream is mostly
 * made of entries that say nothing changed, and a reader who has learned that milestones are usually
 * empty stops reading them. §5 puts the point plainly — the milestone is where gaming becomes visible, so
 * it only exists when there is something to look at.
 *
 * <p>This class reads no clock, which is also why it has no timestamp field. Milestones are numbered in
 * the order they were emitted, and that ordering is the only "when" the loop needs. A wall-clock stamp
 * here would break R5 for nothing: it would make two identical runs produce different milestone text.
 *
 * <h2>Why the diff is the whole point</h2>
 * {@link #text()} prints both scores' full breakdowns next to each other plus the candidate's own
 * description of what it changed. A milestone that said only "score improved from 11.2 to 12.8" is where
 * a reward hack survives, because every hack improves the number — that is what makes it a hack. Seeing
 * <i>which term</i> moved, and reading the candidate's account of what it edited beside it, is what lets a
 * human notice that the objective went up because a guardrail quietly stopped being checked.
 *
 * <p>Season-agnostic core (R7).
 */
public final class Milestone {

    /** Why a milestone was emitted. More than one can apply to the same milestone. */
    public enum Reason {
        /**
         * The first candidate the gate ever accepted. There was no best before it, so the loop went from
         * having no working answer to having one, which is the largest qualitative step it can take.
         */
        FIRST_ACCEPTED_CANDIDATE,
        /**
         * The total improved on the previous best by at least the loop's milestone threshold. An
         * improvement smaller than the threshold still becomes the new best — it passed the gate — but it
         * does not get a milestone, which is what stops a long run from producing hundreds of them.
         */
        IMPROVED_BY_AT_LEAST_THE_THRESHOLD,
        /**
         * A gate clause that was not holding for the previous best is holding now. §5's "qualitatively new
         * working capability": the answer is not merely better-scoring, it satisfies something it did not
         * satisfy before.
         *
         * <p><b>No transition can reach this today, and that is written down on purpose.</b> Both scores
         * being compared belong to accepted candidates, and an accepted verdict has every clause either
         * HOLDS or NOT_YET_APPLICABLE — the gate rejects anything else. The one move available,
         * {@code IMPROVES_ON_LAST_BEST} going NOT_YET_APPLICABLE to HOLDS on the second candidate ever
         * accepted, is explicitly excluded: it means the clause stopped being vacuous because there is
         * finally something to beat, not that the answer gained anything. Counting it would emit a
         * milestone on every second acceptance however small the improvement, defeating the threshold and
         * captioning it as a new capability.
         *
         * <p>The check is kept rather than deleted because it is the alarm for a change to the gate's
         * clause set — a fifth clause, or one that can hold conditionally, makes it load-bearing
         * immediately, and a loop that had quietly stopped noticing new capabilities is a bad thing to
         * discover late.
         */
        A_GATE_CLAUSE_NOW_HOLDS,
        /**
         * A guardrail that was over budget for the previous best, or was not in its envelope at all, is
         * checked and holding now. The envelope is guarding something it was not guarding before, which is
         * a real gain in what the loop knows about its own answer even when the score barely moves.
         *
         * <p>Note that "was UNKNOWN, is now holding" is <b>not</b> one of the routes here, though it
         * sounds like the obvious one. An UNKNOWN guardrail blocks the gate, so a score carrying one never
         * became a last-best and there is no previous best for it to have been UNKNOWN in. The two
         * reachable routes are a soft-budget overrun that has been brought back inside its budget, and a
         * guardrail newly added to the envelope.
         */
        A_GUARDRAIL_IS_NOW_CHECKED_AND_HOLDING
    }

    private final int number;
    private final List<Reason> reasons;
    private final Scorer.Score previousBest;
    private final Scorer.Score newBest;
    private final String candidateDescription;
    private final List<String> newlyHolding;

    Milestone(int number, List<Reason> reasons, Scorer.Score previousBest, Scorer.Score newBest,
            String candidateDescription, List<String> newlyHolding) {
        this.number = number;
        this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        this.previousBest = previousBest;
        this.newBest = newBest;
        this.candidateDescription = candidateDescription;
        this.newlyHolding = Collections.unmodifiableList(new ArrayList<>(newlyHolding));
    }

    /**
     * Which milestone this is, counting from 1 in emission order. The loop's only notion of "when".
     *
     * @return which milestone this is, counting from 1 in emission order
     */
    public int number() {
        return number;
    }

    /**
     * Every reason this milestone was emitted. Never empty.
     *
     * @return every reason this milestone was emitted
     */
    public List<Reason> reasons() {
        return reasons;
    }

    /** {@return the best before this one, or null when this is {@link Reason#FIRST_ACCEPTED_CANDIDATE}} */
    public Scorer.Score previousBest() {
        return previousBest;
    }

    /** {@return the new best} */
    public Scorer.Score newBest() {
        return newBest;
    }

    /** {@return what the candidate said it changed, in its own words} */
    public String candidateDescription() {
        return candidateDescription;
    }

    /**
     * The clauses and guardrails that are holding now and were not before. Empty when the milestone is
     * purely a score improvement.
     *
     * <p>Each entry says what kind of thing it is, so the list can be read without a second list beside it
     * explaining which half is which: a gate clause reads {@code "clause ENVELOPE_HOLDS"} and an envelope
     * term reads {@code "guardrail loopPeriodMs"}. Clauses come first, then guardrails, each in
     * declaration order.
     *
     * @return an unmodifiable list of the newly-holding clause and guardrail names
     */
    public List<String> newlyHolding() {
        return newlyHolding;
    }

    /**
     * How much the total moved. Zero when there was no previous best.
     *
     * @return the rise in {@link Scorer.Score#total()} over the previous best, in the objective's own
     *         units, bigger-is-better
     */
    public double improvement() {
        if (previousBest == null) {
            return 0.0;
        }
        return newBest.total() - previousBest.total();
    }

    /**
     * The milestone as a block of text: why it fired, what changed in the candidate's own words, and both
     * breakdowns side by side. This is the thing a human actually reads, so it spells everything out
     * rather than assuming the reader will go and look the scores up.
     *
     * <p>Every line ends in a literal {@code '\n'} rather than the platform separator, so the same run
     * produces the same bytes on a student's Windows machine and on a CI runner (R5).
     *
     * @return the whole milestone, newline-separated, ending in a newline
     */
    public String text() {
        StringBuilder out = new StringBuilder();
        out.append("MILESTONE ").append(number).append('\n');
        out.append("  why: ").append(reasons).append('\n');
        if (!newlyHolding.isEmpty()) {
            out.append("  newly holding: ").append(newlyHolding).append('\n');
        }
        out.append("  what the candidate says it changed:\n");
        out.append("    ").append(candidateDescription).append('\n');

        out.append("  previous best:\n");
        if (previousBest == null) {
            out.append("    (none — this is the first candidate the gate accepted)\n");
        } else {
            out.append(indent(previousBest.breakdown()));
        }
        out.append("  new best:\n");
        out.append(indent(newBest.breakdown()));
        return out.toString();
    }

    /** Indents a breakdown by four spaces so it reads as a block nested under its heading. */
    private static String indent(String breakdown) {
        StringBuilder out = new StringBuilder();
        for (String line : breakdown.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            out.append("    ").append(line).append('\n');
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return text();
    }
}
