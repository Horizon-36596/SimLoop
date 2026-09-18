package org.horizon36596.simloop.sim;

import org.psilynx.psikit.core.LogTable;
import org.psilynx.psikit.core.rlog.RLOGReplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares two RLOG files by <b>decoded</b> field value, not raw bytes (BACKLOG B3,
 * {@code docs/process/scorer-interface.md} §2/§7). Raw {@code .rlog} bytes are not run-to-run
 * stable even for a fully deterministic scenario, but this is narrower than PsiKit's hash-map key
 * ordering being unstable in general: empirically verified 2026-07-18 against reruns of
 * {@code DrivetrainSimTest} that every robot-authored field decodes identically and the only keys
 * that ever differ are PsiKit's own async RLOG-writer-thread metadata (real-time queue depth / a
 * startup console line), which are excluded here by name because they reflect writer timing, not
 * robot state. Any other differing key — including one present in only one run's frame — is a real
 * mismatch, not filtered.
 */
public final class RlogDecodedCompare {

    /**
     * PsiKit's own writer-thread metadata: real-time queue depth and a startup console line. These reflect
     * when the writer thread happened to be scheduled, not what the robot did, so two identical runs
     * legitimately differ on them and {@link #compare} ignores them.
     *
     * <p>Public because anything that scores a run has to refuse the same keys. A field the replay check
     * ignores must not be scoreable: an objective reading {@code RealOutputs/Logger/QueuedCycles} would
     * otherwise be scored on a number that {@code compare} cannot see, so a candidate could be reported as
     * REPRODUCED while the field its score came from did not reproduce at all. (2026-09-17 adversarial
     * review, finding replay-skips-scoreable-fields.)
     */
    public static final Set<String> EXCLUDED_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "RealOutputs/Console",
            "RealOutputs/Logger/QueuedCycles")));

    private RlogDecodedCompare() {
    }

    /** One decoded-field mismatch between two runs; {@code frameIndex} is -1 for a frame-count mismatch. */
    public static final class Mismatch {
        /** Which frame differed, counting from 0; {@code -1} when the two runs had different frame counts. */
        public final int frameIndex;

        /** The RLOG key whose decoded value differed. */
        public final String key;

        /** The value the first run logged under {@link #key}, rendered the way PsiKit renders it. */
        public final String valueA;

        /** The value the second run logged under {@link #key}, rendered the way PsiKit renders it. */
        public final String valueB;

        Mismatch(int frameIndex, String key, String valueA, String valueB) {
            this.frameIndex = frameIndex;
            this.key = key;
            this.valueA = valueA;
            this.valueB = valueB;
        }

        /**
         * {@return true if this entry reports the two runs having a different total frame count, not a field diff}
         */
        public boolean isFrameCountMismatch() {
            return frameIndex == -1;
        }

        @Override
        public String toString() {
            return "frame " + frameIndex + " key " + key + ": " + valueA + " vs " + valueB;
        }
    }

    /**
     * Decodes both RLOG files via PsiKit's {@link RLOGReplay} and returns every decoded-field
     * mismatch outside PsiKit's own writer-metadata exclude list, frame by frame. An empty list means
     * the two runs satisfy the decoded-field-equality contract.
     *
     * @param pathA the first RLOG to read
     * @param pathB the second RLOG to read
     * @return every mismatch found, in frame order; empty when the two runs agree
     * @throws IOException if either RLOG cannot be read
     */
    public static List<Mismatch> compare(Path pathA, Path pathB) throws IOException {
        return compare(pathA, pathB, EXCLUDED_KEYS);
    }

    /**
     * As {@link #compare(Path, Path)}, but with a caller-supplied exclude set instead of the PsiKit default.
     *
     * @param pathA        the first RLOG to read
     * @param pathB        the second RLOG to read
     * @param excludedKeys keys to ignore entirely, in place of PsiKit's writer-metadata list
     * @return every mismatch found outside {@code excludedKeys}, in frame order
     * @throws IOException if either RLOG cannot be read
     */
    public static List<Mismatch> compare(Path pathA, Path pathB, Set<String> excludedKeys) throws IOException {
        List<Map<String, String>> framesA = decode(pathA);
        List<Map<String, String>> framesB = decode(pathB);

        List<Mismatch> mismatches = new ArrayList<>();
        if (framesA.size() != framesB.size()) {
            mismatches.add(new Mismatch(-1, "<frame count>",
                    String.valueOf(framesA.size()), String.valueOf(framesB.size())));
        }

        int frameCount = Math.min(framesA.size(), framesB.size());
        for (int i = 0; i < frameCount; i++) {
            Map<String, String> a = framesA.get(i);
            Map<String, String> b = framesB.get(i);
            Set<String> keys = new TreeSet<>();
            keys.addAll(a.keySet());
            keys.addAll(b.keySet());
            for (String key : keys) {
                if (excludedKeys.contains(key)) {
                    continue;
                }
                String valueA = a.get(key);
                String valueB = b.get(key);
                if (!Objects.equals(valueA, valueB)) {
                    mismatches.add(new Mismatch(i, key, valueA, valueB));
                }
            }
        }
        return mismatches;
    }

    /**
     * Every field key that appears anywhere in one RLOG, sorted, minus PsiKit's own writer metadata.
     *
     * <p>This is how a test asserts what a subsystem <i>actually published</i>, rather than asserting
     * against a list of key names copied out of that subsystem — which would pass no matter what the
     * subsystem did. The logging contract's rule 2 (units live in the key name, because AdvantageScope
     * shows a key with no javadoc beside it) is only checkable against the real artifact.
     *
     * @param path the RLOG to read
     * @return the sorted key names, minus PsiKit's own writer metadata
     * @throws IOException if the RLOG cannot be read
     */
    public static Set<String> decodedKeys(Path path) throws IOException {
        Set<String> keys = new TreeSet<>();
        for (Map<String, String> frame : decode(path)) {
            keys.addAll(frame.keySet());
        }
        keys.removeAll(EXCLUDED_KEYS);
        return keys;
    }

    /**
     * Every frame of one RLOG, in order, each as its decoded {@code key -> value} map (values rendered the
     * way PsiKit renders them: {@code "12.5"}, {@code "true"}, {@code "HOLDING"}).
     *
     * <p>Public so a test can assert what a subsystem published <b>by value</b>, not just by key name —
     * {@link #decodedKeys(Path)} proves a key called {@code measuredIn} exists, and only this proves the
     * number under it is actually inches.
     *
     * <p><b>Not test-only any more.</b> {@link ScenarioRunner#run} calls this on every real scenario run, to
     * check that the file it just wrote holds one frame per tick before handing it back (BACKLOG B32). This
     * method is therefore on the production path, and its cost and its failure modes are a scenario's cost
     * and failure modes. The rest of this class is still verification-only.
     *
     * @param path the RLOG to read
     * @return one map per frame, in order, each holding the whole decoded robot state at that frame
     * @throws IOException if the RLOG cannot be read
     */
    public static List<Map<String, String>> decode(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) {
            throw new IllegalArgumentException(
                    "RLOG file missing or empty, cannot decode: " + path
                    + " (PsiKit's RLOGReplay silently decodes a missing file as zero frames, which would "
                    + "otherwise let two missing/failed logs compare as a false 'no mismatches' pass)");
        }
        RLOGReplay replay = new RLOGReplay(path.toString());
        replay.start();
        LogTable table = new LogTable(0);
        List<Map<String, String>> frames = new ArrayList<>();
        while (replay.updateTable(table)) {
            Map<String, String> snapshot = new TreeMap<>();
            for (Map.Entry<String, LogTable.LogValue> entry : table.getAll(true).entrySet()) {
                snapshot.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            frames.add(snapshot);
        }
        return frames;
    }
}
