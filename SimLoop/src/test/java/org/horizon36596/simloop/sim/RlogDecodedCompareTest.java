package org.horizon36596.simloop.sim;

import org.junit.jupiter.api.Test;

import org.psilynx.psikit.core.LogTable;
import org.psilynx.psikit.core.rlog.RLOGWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code RlogDecodedCompare}'s contract (BACKLOG B3): decoded robot fields must match between
 * two runs; PsiKit's own writer-thread metadata (Console / QueuedCycles) must NOT be compared; any
 * other differing key -- including a key present in only one run's frame, or the two runs having a
 * different total frame count -- must be reported.
 */
public class RlogDecodedCompareTest {

    @Test
    public void identicalRobotFieldsWithDifferingPsikitMetaProduceNoMismatch() throws IOException {
        Path dir = Files.createTempDirectory("rlog-compare-test");
        Path pathA = writeLog(dir, "run-a", 1.0, 111);
        Path pathB = writeLog(dir, "run-b", 1.0, 222);

        List<RlogDecodedCompare.Mismatch> mismatches = RlogDecodedCompare.compare(pathA, pathB);

        assertTrue(mismatches.isEmpty(), "expected no mismatches, got: " + mismatches);
    }

    @Test
    public void differingRobotFieldIsReportedAsAMismatch() throws IOException {
        Path dir = Files.createTempDirectory("rlog-compare-test");
        Path pathA = writeLog(dir, "run-a", 1.0, 111);
        Path pathB = writeLog(dir, "run-b", 2.0, 111);

        List<RlogDecodedCompare.Mismatch> mismatches = RlogDecodedCompare.compare(pathA, pathB);

        assertEquals(1, mismatches.size());
        assertEquals("RealOutputs/Drive/pose/x", mismatches.get(0).key);
    }

    @Test
    public void differingFrameCountIsReportedAsAMismatch() throws IOException {
        Path dir = Files.createTempDirectory("rlog-compare-test");
        Path pathA = writeLogWithFrames(dir, "run-a", 2);
        Path pathB = writeLogWithFrames(dir, "run-b", 1);

        List<RlogDecodedCompare.Mismatch> mismatches = RlogDecodedCompare.compare(pathA, pathB);

        assertEquals(1, mismatches.size());
        assertTrue(mismatches.get(0).isFrameCountMismatch(), "expected a frame-count mismatch, got: " + mismatches);
        assertEquals("2", mismatches.get(0).valueA);
        assertEquals("1", mismatches.get(0).valueB);
    }

    @Test
    public void keyPresentInOnlyOneRunIsReportedAsAMismatch() throws IOException {
        Path dir = Files.createTempDirectory("rlog-compare-test");
        Path pathA = writeLogWithExtraKey(dir, "run-a", true);
        Path pathB = writeLogWithExtraKey(dir, "run-b", false);

        List<RlogDecodedCompare.Mismatch> mismatches = RlogDecodedCompare.compare(pathA, pathB);

        assertEquals(1, mismatches.size());
        assertTrue(!mismatches.get(0).isFrameCountMismatch());
        assertEquals("RealOutputs/Drive/pose/y", mismatches.get(0).key);
    }

    @Test
    public void callerSuppliedExcludeSetOverridesTheDefault() throws IOException {
        Path dir = Files.createTempDirectory("rlog-compare-test");
        Path pathA = writeLog(dir, "run-a", 1.0, 111);
        Path pathB = writeLog(dir, "run-b", 2.0, 111);

        // With an exclude set that also covers the robot field, the one real difference is filtered too.
        List<RlogDecodedCompare.Mismatch> mismatches = RlogDecodedCompare.compare(pathA, pathB,
                java.util.Collections.singleton("RealOutputs/Drive/pose/x"));

        assertTrue(mismatches.isEmpty(), "expected no mismatches with a caller override, got: " + mismatches);
    }

    /** Writes a single-frame RLOG with a robot field plus PsiKit-meta-shaped keys at the given values. */
    private static Path writeLog(Path dir, String name, double poseX, int queuedCycles) {
        RLOGWriter writer = new RLOGWriter(dir.toString(), name);
        writer.start();
        LogTable table = new LogTable(0.0);
        table.put("RealOutputs/Drive/pose/x", poseX);
        table.put("RealOutputs/Logger/QueuedCycles", queuedCycles);
        table.put("RealOutputs/Console", "line-" + queuedCycles);
        writer.putTable(table);
        writer.end();
        return dir.resolve(name + ".rlog");
    }

    /** Writes an RLOG with {@code frameCount} frames, each carrying the same robot field value. */
    private static Path writeLogWithFrames(Path dir, String name, int frameCount) {
        RLOGWriter writer = new RLOGWriter(dir.toString(), name);
        writer.start();
        for (int i = 0; i < frameCount; i++) {
            LogTable table = new LogTable(i * 0.02);
            table.put("RealOutputs/Drive/pose/x", 1.0);
            writer.putTable(table);
        }
        writer.end();
        return dir.resolve(name + ".rlog");
    }

    /** Writes a single-frame RLOG that includes an extra robot key only when {@code includeExtraKey} is true. */
    private static Path writeLogWithExtraKey(Path dir, String name, boolean includeExtraKey) {
        RLOGWriter writer = new RLOGWriter(dir.toString(), name);
        writer.start();
        LogTable table = new LogTable(0.0);
        table.put("RealOutputs/Drive/pose/x", 1.0);
        if (includeExtraKey) {
            table.put("RealOutputs/Drive/pose/y", 2.0);
        }
        writer.putTable(table);
        writer.end();
        return dir.resolve(name + ".rlog");
    }
}
