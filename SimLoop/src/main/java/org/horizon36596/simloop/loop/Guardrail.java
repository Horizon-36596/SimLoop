package org.horizon36596.simloop.loop;

/**
 * One safety-envelope term: something that must stay true no matter how good the objective gets
 * ({@code docs/process/scorer-interface.md} §3).
 *
 * <h2>The clause this whole class exists for: a guardrail that cannot check itself says UNKNOWN</h2>
 * A guardrail evaluates a finished run and returns a {@link Verdict}. There are three of them, not two,
 * and the third is the point:
 *
 * <ul>
 *   <li>{@link Verdict#holds()} — checked, and fine.</li>
 *   <li>{@link Verdict#violated(String)} — checked, and broken. Effectively infinite penalty, so the run
 *       is ineligible regardless of its objective.</li>
 *   <li>{@link Verdict#unknown(String)} — <b>could not be checked from this run at all.</b></li>
 * </ul>
 *
 * <p>Several of §3's envelope terms are not log facts. "All invariant-tier tests green" is a Gradle exit
 * code. "{@code R8}: this code passed in sim first" is a fact about history. "Logging overhead stays
 * under its loop-time budget" needs a budget nobody has measured yet. A guardrail for one of those,
 * handed only an RLOG, cannot answer — and if it quietly returns zero penalty, the envelope silently
 * degrades into "whatever happened to be loggable", which is exactly the reward hack the envelope exists
 * to stop. So it says {@code UNKNOWN}, loudly, and E2's acceptance gate decides what to do about it.
 *
 * <p><b>{@code UNKNOWN} is not a pass and not a failure.</b> It carries no penalty — inventing one would
 * be a made-up number — and it must never be read as "holds". {@link Scorer} keeps unknowns in its
 * breakdown and counts them separately for precisely this reason.
 *
 * <h2>Invariants only, never implementation facts</h2>
 * {@code kP > 0} is not an envelope term (§3, §4): it belongs to the implementation tier, and putting it
 * here would block a legitimate controller swap. An envelope term survives a redesign.
 */
public interface Guardrail {

    /** What a guardrail concluded about one run. */
    enum Status {
        /** Checked against the run; the invariant holds. */
        HOLDS,
        /** Checked against the run; the invariant is broken. Effectively infinite penalty. */
        VIOLATED,
        /**
         * Checked against the run; a soft budget was exceeded. A finite, graded penalty the objective is
         * allowed to trade against (§1). Distinct from {@link #HOLDS} because a term that charged the
         * score is not a term that was fine, and a breakdown reading {@code HOLDS(5.0)} says otherwise.
         */
        OVER_BUDGET,
        /** Could not be checked from this run. Not a pass, not a failure, and never zero penalty. */
        UNKNOWN
    }

    /**
     * The penalty applied to a violated envelope term.
     *
     * <p>{@link Double#POSITIVE_INFINITY} rather than a large finite number, so no objective — however
     * good — can ever buy its way past a violation. A finite penalty is a price; this is a wall.
     */
    double ENVELOPE_VIOLATION_PENALTY = Double.POSITIVE_INFINITY;

    /**
     * What this guardrail is called, for the score breakdown a human reads.
     *
     * <p><b>Abstract on purpose, which is also why this interface is not a {@code @FunctionalInterface}.</b>
     * It was briefly a {@code default} returning {@code getClass().getSimpleName()}, and that is a trap: a
     * lambda cannot override a default method, so every lambda-built guardrail would have taken the JVM's
     * synthesized lambda class name instead. Those names vary by JVM and by run, which would make the
     * breakdown's keys unstable across a replay (R5) and would let two unrelated custom guardrails collide
     * on one name. Two abstract methods means the compiler rejects the bare lambda instead, and the author
     * has to say what the term is called.
     *
     * @return a stable name for this term, used as its key in the score breakdown
     */
    String name();

    /**
     * Checks one finished run.
     *
     * <p><b>Return a verdict; do not throw.</b> If this term needs a key the run does not have, return
     * {@link Verdict#unknown(String)} — that is what {@code UNKNOWN} is for. Reaching straight for
     * {@link RunResult#metric(String)} without checking {@link RunResult#hasKey(String)} first throws
     * {@link RunResult.MetricNotFoundException} out through {@link Scorer#score(RunResult)}, which stops
     * the loop rather than scoring the run ineligible. That is loud and safe — nothing gets promoted on a
     * crash — but it is not the behaviour anybody wants, and the fix is one {@code hasKey} check.
     *
     * @param run the finished run to check
     * @return this term's conclusion about {@code run}; never null
     */
    Verdict evaluate(RunResult run);

    /** A guardrail's conclusion about one run: a status, a penalty, and why. */
    final class Verdict {

        private final Status status;
        private final double penalty;
        private final String reason;

        private Verdict(Status status, double penalty, String reason) {
            this.status = status;
            this.penalty = penalty;
            this.reason = reason;
        }

        /**
         * Checked, and fine. No penalty.
         *
         * @return checked, and fine
         */
        public static Verdict holds() {
            return new Verdict(Status.HOLDS, 0.0, "holds");
        }

        /**
         * Checked, and broken. Infinite penalty, so the run cannot become the new best.
         *
         * @param reason what broke, in a sentence a human can act on
         * @return a VIOLATED verdict carrying an infinite penalty
         */
        public static Verdict violated(String reason) {
            return new Verdict(Status.VIOLATED, ENVELOPE_VIOLATION_PENALTY, requireReason(reason,
                    "a violated guardrail must say what broke"));
        }

        /**
         * Graded penalty for a soft budget overrun (§1: "graded when a soft budget is exceeded").
         *
         * <p>Distinct from {@link #violated(String)}: a soft budget can be traded against the objective,
         * an envelope term cannot. Anything you would be upset to lose belongs in {@code violated},
         * not here (§7).
         *
         * @param penalty how much to subtract; must be finite and non-negative. Infinity is rejected —
         *                use {@link #violated(String)}, which says what it means.
         * @param reason  what the penalty is being charged for, in a sentence a human can act on
         * @return an OVER_BUDGET verdict carrying {@code penalty}
         */
        public static Verdict overBudget(double penalty, String reason) {
            if (!Double.isFinite(penalty) || penalty < 0.0) {
                throw new IllegalArgumentException("a soft-budget penalty must be finite and non-negative, "
                        + "got " + penalty + " -- for an envelope violation use Verdict.violated(reason), "
                        + "which is infinite on purpose and says so");
            }
            return new Verdict(Status.OVER_BUDGET, penalty, requireReason(reason,
                    "a soft-budget penalty must say what it is charging for"));
        }

        /**
         * Could not be checked from this run.
         *
         * @param reason why not — "needs a Gradle result", "needs the edit history", "no budget measured
         *               yet". This string is what tells a human whether the gap is worth closing.
         * @return an UNKNOWN verdict, which never counts as a pass
         */
        public static Verdict unknown(String reason) {
            return new Verdict(Status.UNKNOWN, 0.0, requireReason(reason,
                    "an UNKNOWN guardrail must say why it could not check itself, or nobody can tell "
                            + "whether the gap matters"));
        }

        /** {@return whether this term was checked and fine, checked and broken, or not checkable at all} */
        public Status status() {
            return status;
        }

        /**
         * How much to subtract from the objective.
         *
         * <p>Zero for {@link Status#UNKNOWN} — but a caller must never infer "no penalty means fine".
         * {@link #isUnknown()} is the question to ask; {@link Scorer} asks it.
         *
         * @return the penalty in the objective's own units; zero for HOLDS and UNKNOWN, infinite for
         *         VIOLATED
         */
        public double penalty() {
            return penalty;
        }

        /** {@return why, in a sentence a human can act on} */
        public String reason() {
            return reason;
        }

        /** {@return true when this term was checked and is broken} */
        public boolean isViolation() {
            return status == Status.VIOLATED;
        }

        /** {@return true when this term could not be checked from the run at all} */
        public boolean isUnknown() {
            return status == Status.UNKNOWN;
        }

        /**
         * {@return true when this term was checked, holds as an invariant, but charged a graded soft-budget penalty}
         */
        public boolean isOverBudget() {
            return status == Status.OVER_BUDGET;
        }

        /**
         * True when this term was checked and is fine.
         *
         * <p>Present so "did it hold?" reads the same way as the other three questions. Without it a caller
         * has to write {@code status() == Status.HOLDS} for this one case alone, and the asymmetry invites
         * the reading that anything not violated held — which is the mistake {@code UNKNOWN} exists to stop.
         *
         * @return true only when the status is {@link Status#HOLDS}
         */
        public boolean holdsCleanly() {
            return status == Status.HOLDS;
        }

        private static String requireReason(String reason, String complaint) {
            if (reason == null || reason.trim().isEmpty()) {
                throw new IllegalArgumentException(complaint);
            }
            return reason;
        }

        @Override
        public String toString() {
            return status + "(" + penalty + "): " + reason;
        }
    }
}
