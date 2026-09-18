package org.horizon36596.simloop.loop;

import org.psilynx.psikit.core.LogTable;
import org.psilynx.psikit.core.rlog.RLOGWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Writes the small hand-made RLOGs this package's tests score against.
 *
 * <p>One copy of this, rather than one per test class. The rationale below is the kind that drifts
 * silently when it is pasted into three files, and drifting here means a test that writes to the wrong
 * place and fails for a reason nobody recognises.
 *
 * <h2>Why {@code build/sim} and not a JUnit {@code @TempDir}</h2>
 * PsiKit's RLOG writer thread does not release its file handle the instant the writer is ended, and on
 * Windows a still-open handle makes {@code @TempDir}'s post-test cleanup fail with a
 * {@code FileSystemException} — observed directly, and recorded in {@code ScenarioRunnerTest}'s javadoc.
 * Every sim test in this repo writes to {@code build/sim} for that reason.
 *
 * <h2>Why every name gets a prefix</h2>
 * Two writers on one RLOG path is {@code docs/BACKLOG.md} B11, which fails as a determinism bug that is
 * not one and has already cost this repo two debugging sessions. Callers pass a base name that starts
 * with {@code loop-}, unique within this package and used by nothing else in the build.
 */
final class LoopTestRlogs {

    private LoopTestRlogs() {
    }

    /** What one frame of a test RLOG contains. */
    @FunctionalInterface
    interface Frame {
        /** Fills in the fields this frame logs. */
        void write(LogTable table);
    }

    /**
     * Writes an RLOG with one frame per {@code frames} entry and returns its path.
     *
     * @param baseName file name without the {@code .rlog} suffix; must be unique in this package
     * @param frames   one entry per frame, in order
     */
    static Path write(String baseName, Frame... frames) throws IOException {
        Path dir = simDir();
        RLOGWriter writer = new RLOGWriter(dir.toString(), baseName);
        writer.start();
        for (int i = 0; i < frames.length; i++) {
            // Frame timestamps step by a fixed 20 ms: a sim tick, and fixed so nothing here reads a clock (R5).
            LogTable table = new LogTable(i * 0.02);
            frames[i].write(table);
            writer.putTable(table);
        }
        writer.end();
        return dir.resolve(baseName + ".rlog");
    }

    /** Writes one frame per value, all under {@code key}, and returns the RLOG's path. */
    static Path writeOneKeyPerFrame(String baseName, String key, double... values) throws IOException {
        Frame[] frames = new Frame[values.length];
        for (int i = 0; i < values.length; i++) {
            double value = values[i];
            frames[i] = table -> table.put(key, value);
        }
        return write(baseName, frames);
    }

    /** {@code build/sim}, created if it is not there yet. */
    static Path simDir() throws IOException {
        Path dir = Paths.get("build", "sim");
        Files.createDirectories(dir);
        return dir;
    }
}
