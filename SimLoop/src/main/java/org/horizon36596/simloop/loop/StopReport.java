package org.horizon36596.simloop.loop;

import org.horizon36596.simloop.sim.RlogDecodedCompare;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the loop says when it is stopped: the best answer it found, and proof that the answer reproduces.
 *
 * <h2>The report reproduces before it claims</h2>
 * The loop may be stopped at any moment, and whatever it reports at that point is what a human will act
 * on ({@code docs/process/scorer-interface.md} §5). So before this report presents a score as the best,
 * the candidate's scenario is run <b>a second time</b> and the two runs are compared field by field with
 * {@link RlogDecodedCompare}. If they differ, the report says a determinism failure happened and
 * <b>does not present the score</b>.
 *
 * <p>That ordering is the point of the class. A score that cannot be reproduced is not a small caveat on
 * a real result — it is not a result. The run that produced it may have been the lucky one, and every
 * decision made downstream ("the new gains are better") would rest on a number nobody can get back. Area
 * E's goal sentence is exactly this: the engine never reports an improvement it cannot reproduce.
 *
 * <h2>What this check cannot prove</h2>
 * It proves the scenario produced the same decoded fields twice <i>given a replayer that really
 * re-runs it</i>. A replayer that copied the original RLOG to a new path would satisfy every check
 * here, and this class cannot tell the difference -- it sees two files, not two executions. The only
 * mechanical guard is the refusal to accept a replayer that writes back over the original file.
 * Supplying a replayer that genuinely re-runs the scenario is the caller's responsibility, and in the
 * season glue it is a {@code ScenarioRunner} call that does it.
 *
 * <p>The refusal is enforced in the type, not by asking the reader to check a flag first:
 * {@link #reportedScore()} throws unless {@link #reproduction()} is {@link Reproduction#REPRODUCED}. A
 * caller that wants the number has to have got past the reproduction check to reach it.
 *
 * <p>Season-agnostic core (R7). Reads no clock (R5).
 */
public final class StopReport {

    /** How the second run of the best candidate's scenario came out. */
    public enum Reproduction {
        /** Replayed, and every decoded field matched. The score is safe to report. */
        REPRODUCED,
        /**
         * Replayed, and the decoded fields differed. This is a determinism failure (R5, B3) and the score
         * is not reported — the mismatches are.
         */
        DID_NOT_REPRODUCE,
        /**
         * No replay was attempted, because there is no last-best to replay. The loop was stopped before
         * the gate accepted anything.
         */
        NOT_ATTEMPTED
    }

    /**
     * Runs one accepted candidate's scenario again, from the top, and returns the fresh RLOG.
     *
     * <p>This is a seam rather than a direct {@code ScenarioRunner} call because the driver genuinely
     * cannot do it itself: a {@link RunResult} is a finished log, and it does not carry the robot config,
     * the tick count or the gamepad script that produced it. Whoever ran the scenario the first time is
     * the only one who can run it again the same way, so they hand in the means to do it.
     */
    @FunctionalInterface
    public interface ScenarioReplayer {

        /**
         * Re-runs the scenario that produced {@code accepted} and returns the path to the new RLOG.
         *
         * <p>Must write to a different path from {@code accepted.rlogPath()}. Two writers on one RLOG
         * path is {@code docs/BACKLOG.md} B11, and it fails as a determinism bug that is not one — which
         * is precisely the conclusion this report would then draw.
         *
         * @param accepted the run being reproduced, carrying its OpMode name and its original RLOG path
         * @return the new RLOG, to be compared field by field against the original
         * @throws IOException if the replay could not be run or its RLOG could not be written
         */
        Path replay(RunResult accepted) throws IOException;
    }

    private final Reproduction reproduction;
    private final Scorer.Score bestScore;
    private final String candidateDescription;
    private final List<RlogDecodedCompare.Mismatch> mismatches;
    private final List<Milestone> milestones;
    private final int offeredCount;
    private final int acceptedCount;

    StopReport(Reproduction reproduction, Scorer.Score bestScore, String candidateDescription,
            List<RlogDecodedCompare.Mismatch> mismatches, List<Milestone> milestones,
            int offeredCount, int acceptedCount) {
        this.reproduction = reproduction;
        this.bestScore = bestScore;
        this.candidateDescription = candidateDescription;
        this.mismatches = Collections.unmodifiableList(new ArrayList<>(mismatches));
        this.milestones = Collections.unmodifiableList(new ArrayList<>(milestones));
        this.offeredCount = offeredCount;
        this.acceptedCount = acceptedCount;
    }

    /** {@return how the second run came out} */
    public Reproduction reproduction() {
        return reproduction;
    }

    /** {@return true only when a score is safe to report — replayed, and identical field by field} */
    public boolean hasReportableScore() {
        return reproduction == Reproduction.REPRODUCED;
    }

    /**
     * The best score, once it has been reproduced.
     *
     * @throws IllegalStateException if the candidate did not reproduce, or if there was never one to
     *                               reproduce. Deliberately an exception rather than a null or a
     *                               best-effort number: the one mistake this class exists to prevent is a
     *                               caller printing a score the second run did not agree with.
     * @return the reproduced best score
     */
    public Scorer.Score reportedScore() {
        if (reproduction != Reproduction.REPRODUCED) {
            throw new IllegalStateException(
                    "there is no reportable score: reproduction came out " + reproduction
                            + ". A score the second run did not agree with is not a result, and this "
                            + "report will not hand one out. Read reproduction() and mismatches().");
        }
        return bestScore;
    }

    /**
     * The best score whether or not it reproduced, for diagnosis only, or null when there was never one.
     * Everything a human acts on goes through {@link #reportedScore()}; this is here so a determinism
     * failure can say which number failed to come back.
     *
     * @return the best score as first measured, or null when no candidate was ever accepted
     */
    public Scorer.Score unreproducedScoreForDiagnosis() {
        return bestScore;
    }

    /**
     * Every decoded-field mismatch between the two runs. Empty unless reproduction failed.
     *
     * @return every decoded-field mismatch between the two runs
     */
    public List<RlogDecodedCompare.Mismatch> mismatches() {
        return mismatches;
    }

    /** {@return every milestone the loop emitted, in order} */
    public List<Milestone> milestones() {
        return milestones;
    }

    /** {@return how many candidates were offered to the gate} */
    public int offeredCount() {
        return offeredCount;
    }

    /** {@return how many the gate accepted} */
    public int acceptedCount() {
        return acceptedCount;
    }

    /**
     * The report as a block of text. Literal {@code '\n'} throughout so the same loop produces the same
     * bytes everywhere (R5).
     *
     * @return the whole report, newline-separated, ending in a newline
     */
    public String text() {
        StringBuilder out = new StringBuilder();
        out.append("LOOP STOP REPORT\n");
        out.append("  candidates offered: ").append(offeredCount).append('\n');
        out.append("  candidates accepted: ").append(acceptedCount).append('\n');
        out.append("  milestones emitted: ").append(milestones.size()).append('\n');

        switch (reproduction) {
            case NOT_ATTEMPTED:
                out.append("  result: NO LAST-BEST. The gate accepted nothing, so there is no answer to\n");
                out.append("          report and nothing was replayed.\n");
                break;
            case DID_NOT_REPRODUCE:
                out.append("  result: DETERMINISM FAILURE (R5). The best candidate's scenario was run a\n");
                out.append("          second time and the decoded fields differed, so its score is NOT\n");
                out.append("          reported. Fix the non-determinism before trusting any number here.\n");
                out.append("  mismatches (").append(mismatches.size()).append("):\n");
                for (RlogDecodedCompare.Mismatch mismatch : mismatches) {
                    out.append("    ").append(mismatch).append('\n');
                }
                break;
            case REPRODUCED:
            default:
                out.append("  result: LAST-BEST, REPRODUCED. The scenario was run a second time and every\n");
                out.append("          decoded field matched.\n");
                out.append("  what the accepted candidate says it changed:\n");
                out.append("    ").append(candidateDescription).append('\n');
                for (String line : bestScore.breakdown().split("\n", -1)) {
                    if (!line.isEmpty()) {
                        out.append("    ").append(line).append('\n');
                    }
                }
                break;
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return text();
    }
}
