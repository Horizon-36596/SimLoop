package org.horizon36596.simloop.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.horizon36596.simloop.config.DrivetrainSimConfig;
import org.horizon36596.simloop.config.SimRobotConfig;

import org.junit.jupiter.api.Test;
import org.psilynx.psikit.core.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * {@code ScenarioRunner} never hands back a log that is missing ticks (BACKLOG B32).
 *
 * <p><b>The failure this exists to stop.</b> A run could finish, report no writer fault, write plenty of
 * bytes, and still decode to fewer frames than it ran ticks - the missing ones always at the tail. That
 * was measured in a season repo at roughly one full suite run in three: 1484 frames for 1500 ticks on one
 * scenario, 596 for 600 on another, {@code Logger.getReceiverQueueFault()} false both times.
 *
 * <p>Nothing downstream can detect it. An RLOG is the record of what happened, so a short one silently
 * becomes "what happened", and a scenario scored over the ticks it survived rather than the ticks it ran
 * is the cheapest way there is to win a loop exam.
 *
 * <p><b>The cause, and the fix these tests pin.</b> PsiKit's receiver queue holds 500 frames, its capacity
 * is a hard-coded {@code private static final} with no setter, and its overflow flag does not latch - every
 * successful enqueue resets it to false, so a run that overflows in the middle and recovers reports clean
 * at the end. A simulator has no 50 Hz clock holding it back, so it can produce frames faster than the
 * writer thread drains them and lose the difference. {@code ScenarioRunner} now paces the producer against
 * a frame counter registered behind the writer, which is the only way to see the queue depth PsiKit does
 * not expose. The contract the tests hold it to: <em>if {@code run} returns, the file has every tick.</em>
 */
class ScenarioRunnerTruncationTest {

    private static final double DT = 0.02;

    private static final SimRobotConfig STUB_CONFIG = new SimRobotConfig() {
        @Override public String[] driveMotorNames() { return new String[] {"fl", "fr", "bl", "br"}; }
        @Override public String odometryName() { return "odo"; }
        @Override public DrivetrainSimConfig drivetrain() {
            return new DrivetrainSimConfig() {
                @Override public double trackWidth() { return 12.0; }
                @Override public double wheelBase() { return 12.0; }
                @Override public double wheelRadius() { return 2.0; }
                @Override public double ticksPerInch() { return 100.0; }
                @Override public double maxVelocityTicksPerSecond() { return 1000.0; }
                @Override public double maxAccel() { return 2.0; }
                @Override public double fieldHalfWidth() { return 72.0; }
                @Override public double fieldHalfHeight() { return 72.0; }
            };
        }
    };

    /**
     * A match-length run decodes to exactly one frame per tick, every time, several runs into one JVM.
     *
     * <p>Length and repetition are the point. {@code ScenarioRunnerTest}'s scenario is 25 ticks long and
     * runs twice, which is why it never saw this: the loss is load-dependent and lands at the tail of long
     * runs. 1500 ticks is a full FTC match at 50 Hz, which is the length the season repo was failing at.
     *
     * <p>Two fields per tick is deliberately the LIGHTEST case, not the heaviest. A tick that does almost
     * no work is the one the producer finishes fastest, so it is the one that outruns the writer hardest -
     * which is exactly backwards from where you would look for a load-related bug. The first version of
     * this test used twelve fields and never reproduced.
     */
    @Test
    void aMatchLengthRunDecodesToOneFramePerTick() throws IOException {
        for (int run = 0; run < 5; run++) {
            Path rlog = Paths.get("build", "sim", "truncation-matchlength-" + run + ".rlog");
            runScenario(rlog, 1500, 2);

            assertEveryTickIsPresentInOrder(RlogDecodedCompare.decode(rlog), 1500, "run " + run);
        }
    }

    /**
     * A run long and wide enough to have flooded the old writer path still decodes whole.
     *
     * <p>2000 ticks of 40 fields is four times the queue's capacity in ticks and twenty times the field
     * count of the test above; before the pacing fix it filled the queue in about a tenth of a second.
     * This is the case the runner is now required to survive rather than merely to notice.
     *
     * <p>The loud failure paths are still in {@code ScenarioRunner} - the per-tick fault latch, the
     * decoded-frame count after {@code Logger.end()}, and the stalled-writer budget - so if the pacing ever
     * stops working the run throws rather than quietly returning 1800 frames. This test does not lean on
     * that: it checks the contents frame by frame, which nothing in production looks at.
     */
    @Test
    void aHeavyRunThatWouldHaveFloodedTheWriterStillDecodesWhole() throws IOException {
        Path rlog = Paths.get("build", "sim", "truncation-overrun.rlog");
        runScenario(rlog, 2000, 40);

        assertEveryTickIsPresentInOrder(RlogDecodedCompare.decode(rlog), 2000, "the heavy run");
    }

    /**
     * Asserts the decoded log is the record of the run: tick n's own value, at frame n, for every n.
     *
     * <p><b>Why this and not {@code assertEquals(ticks, frames.size())}.</b> {@code ScenarioRunner} counts
     * the frames itself now and throws if the count is wrong, so a test that counted them again could not
     * fail on its own - if {@code run} returned at all, the count was already right, and the assertion would
     * be decorative. Content is the part nothing else checks. The scenario body writes {@code Test/field0}
     * equal to the tick number, so a log that is the right length but has a frame duplicated, dropped from
     * the middle, or written out of order fails here and passes the production guard.
     *
     * @param frames  every decoded frame, in file order
     * @param ticks   how many ticks the scenario ran
     * @param whichRun named in the failure message, since these run in a loop
     */
    private static void assertEveryTickIsPresentInOrder(
            List<Map<String, String>> frames, int ticks, String whichRun) {
        assertEquals(ticks, frames.size(), whichRun + " must decode to one frame per tick");

        for (int frame = 0; frame < ticks; frame++) {
            String expected = String.valueOf((double) (frame + 1));
            assertEquals(expected, frames.get(frame).get("RealOutputs/Test/field0"),
                    whichRun + ": frame " + frame + " does not carry tick " + (frame + 1) + "'s own value, "
                            + "so the log is the right length and still not what happened");
        }
    }

    private static void runScenario(Path rlogPath, int ticks, int fieldsPerTick) {
        FakeTimer timer = new FakeTimer();
        int[] ticksSeen = {0};
        ScenarioRunner.run("TruncationScenario", STUB_CONFIG, timer, ticks, DT, rlogPath, deltaTime -> {
            ticksSeen[0]++;
            for (int field = 0; field < fieldsPerTick; field++) {
                Logger.recordOutput("Test/field" + field, ticksSeen[0] * (field + 1.0));
            }
        });
    }
}
