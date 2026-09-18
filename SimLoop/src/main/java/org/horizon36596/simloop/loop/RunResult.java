package org.horizon36596.simloop.loop;

import org.horizon36596.simloop.sim.RlogDecodedCompare;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * One finished sim run, read back off disk so it can be scored.
 *
 * <h2>Decoded fields only, never raw bytes</h2>
 * Every number this class exposes comes from {@link RlogDecodedCompare#decode(Path)} — PsiKit's own
 * decoder — and never from the RLOG's bytes. Two identical runs write byte-different RLOGs because
 * PsiKit's key ordering comes from a hash map ({@code docs/BACKLOG.md} B3); the decoded numeric fields
 * are what is actually deterministic, and they are the only thing worth scoring.
 *
 * <h2>A short log is a violation, not a low score</h2>
 * The run is built with the frame count it was <b>supposed</b> to produce, because
 * {@code ScenarioRunner.run} is told a tick count up front — so "how many frames should there be" is
 * known before the run rather than inferred from whatever the run happened to write. A candidate that
 * scores well by logging less is the "win by not logging" hack from
 * {@code docs/process/scorer-interface.md} §7, and {@link #isComplete()} is what lets a guardrail see it.
 *
 * <h2>A missing metric fails loudly</h2>
 * {@link #metric(String)} throws when a key is absent, naming the key and offering the closest matches.
 * A typo'd metric name must never read as a legitimate zero — that is a silent scoring lie, and it is
 * the failure mode this whole class is shaped around.
 *
 * <p>Immutable and frame-ordered. Reading a run twice gives the same numbers, which is what makes a
 * replay comparison meaningful (domain R5).
 */
public final class RunResult {

    /**
     * How Java prints a non-finite double, as a standalone token: {@code NaN}, {@code Infinity},
     * {@code -Infinity}. The surrounding {@code (?<![A-Za-z0-9_])} / {@code (?![A-Za-z0-9_])} guards mean
     * it matches the number inside {@code "[1.0, NaN, 3.0]"} but not the word inside a state name like
     * {@code "NaNoScale"}.
     */
    private static final Pattern NON_FINITE_TOKEN =
            Pattern.compile("(?<![A-Za-z0-9_])-?(?:NaN|Infinity)(?![A-Za-z0-9_])");

    private final String opModeName;
    private final Path rlogPath;
    private final int expectedFrameCount;
    private final List<Map<String, String>> frames;
    private final Set<String> keys;

    private RunResult(String opModeName, Path rlogPath, int expectedFrameCount,
            List<Map<String, String>> frames, Set<String> keys) {
        this.opModeName = opModeName;
        this.rlogPath = rlogPath;
        this.expectedFrameCount = expectedFrameCount;
        this.frames = Collections.unmodifiableList(frames);
        this.keys = Collections.unmodifiableSet(keys);
    }

    /**
     * Reads a finished run off disk.
     *
     * @param opModeName         what ran, for error messages a human has to act on
     * @param rlogPath           the RLOG {@code ScenarioRunner} wrote
     * @param expectedFrameCount the tick count the scenario was told to run; a run with fewer decoded
     *                           frames than this is incomplete (see {@link #isComplete()}). Must be
     *                           positive — a run that expected zero frames cannot be scored at all.
     * @return the decoded run, ready to score
     * @throws IOException              if the RLOG cannot be read
     * @throws IllegalArgumentException if the file is missing/empty (the decoder's own check) or
     *                                  {@code expectedFrameCount} is not positive
     */
    public static RunResult fromRlog(String opModeName, Path rlogPath, int expectedFrameCount)
            throws IOException {
        if (expectedFrameCount <= 0) {
            throw new IllegalArgumentException("expectedFrameCount must be positive, got "
                    + expectedFrameCount + " -- a run with no expected frames cannot be scored, and "
                    + "defaulting it to 0 would make every short run look complete");
        }
        List<Map<String, String>> decoded = new ArrayList<>();
        Set<String> decodedKeys = new TreeSet<>();
        for (Map<String, String> frame : RlogDecodedCompare.decode(rlogPath)) {
            // Drop the keys the replay check cannot see, so nothing scoreable is invisible to it.
            //
            // RlogDecodedCompare.compare ignores PsiKit's writer-thread metadata because it records when the
            // writer was scheduled rather than what the robot did. Leaving those keys in the RunResult meant
            // an objective or a guardrail could be written against one of them, and then the stop report
            // would say REPRODUCED - because compare saw no mismatch - about a run whose scored field had in
            // fact changed. A field that cannot be checked must not be scoreable.
            // (2026-09-17 adversarial review, finding replay-skips-scoreable-fields.)
            Map<String, String> scoreable = new LinkedHashMap<>(frame);
            scoreable.keySet().removeAll(RlogDecodedCompare.EXCLUDED_KEYS);

            // Each frame is wrapped, not just the outer list: this class calls itself immutable, and a
            // frame map handed out raw is a caller's chance to edit the evidence after the fact.
            decoded.add(Collections.unmodifiableMap(scoreable));
            decodedKeys.addAll(scoreable.keySet());
        }
        if (decoded.isEmpty()) {
            throw new IllegalArgumentException("run '" + opModeName + "' decoded zero frames from "
                    + rlogPath + ", so there is nothing to score. Rejected here rather than passed on, "
                    + "because a run with no frames makes every envelope term vacuously true -- "
                    + "'no NaN was logged' is not reassuring when nothing was logged at all.");
        }
        return new RunResult(opModeName, rlogPath, expectedFrameCount, decoded, decodedKeys);
    }

    /**
     * What ran. Recorded by {@code ScenarioRunner} as PsiKit metadata and repeated here for messages.
     *
     * @return the OpMode name; never null
     */
    public String opModeName() {
        return opModeName;
    }

    /**
     * Where the RLOG this was decoded from lives.
     *
     * @return the path the run was read from; never null
     */
    public Path rlogPath() {
        return rlogPath;
    }

    /**
     * How many frames the scenario was told to produce, known before the run.
     *
     * @return the expected frame count; always positive
     */
    public int expectedFrameCount() {
        return expectedFrameCount;
    }

    /**
     * How many frames actually decoded.
     *
     * @return the decoded frame count; always at least 1
     */
    public int frameCount() {
        return frames.size();
    }

    /**
     * True when the run produced every frame it was supposed to.
     *
     * <p>Deliberately exact rather than "within a few frames of". A slack window here is a budget a
     * reward-hacking edit can spend: drop four frames, score better, stay inside the tolerance. If a
     * scenario legitimately produces fewer frames than its tick count, that is a fact about the harness
     * worth fixing in the harness, not worth absorbing here.
     *
     * @return true only when {@link #frameCount()} equals {@link #expectedFrameCount()} exactly
     */
    public boolean isComplete() {
        return frames.size() == expectedFrameCount;
    }

    /**
     * Every key that appears anywhere in the run, sorted.
     *
     * @return an unmodifiable set of RLOG keys
     */
    public Set<String> keys() {
        return keys;
    }

    /**
     * Whether a key was logged at all.
     *
     * @param key the RLOG key to look for
     * @return true if {@code key} was logged in at least one frame
     */
    public boolean hasKey(String key) {
        return keys.contains(key);
    }

    /**
     * Every decoded frame, in order, each as {@code key -> rendered value}.
     *
     * @return an unmodifiable list of unmodifiable frame maps
     */
    public List<Map<String, String>> frames() {
        return frames;
    }

    /**
     * Every value logged under {@code key}, in frame order, parsed as a double.
     *
     * @throws MetricNotFoundException if no frame logged the key — never a zero, never an empty list.
     *                                 An absent metric is a broken objective, and a broken objective that
     *                                 scores 0.0 looks exactly like a legitimately bad run.
     * @throws MetricNotNumericException if any frame's value under the key will not parse as a double.
     *                                 A {@code try}/{@code catch} returning 0.0 here would turn a boolean
     *                                 or a state name into a plausible score.
     *
     * <p><b>A non-finite value is returned, not thrown on.</b> {@code Double.parseDouble} accepts the
     * strings {@code "NaN"}, {@code "Infinity"} and {@code "-Infinity"}, so those come back in the list as
     * they were logged. That is deliberate: this class reads the log faithfully and leaves judgement to
     * the terms whose job it is. {@link #nonFiniteKeys()} is what finds them, and
     * {@code EnvelopeGuardrails.noNonFiniteValues()} is what makes them a violation. Throwing here instead
     * would turn a run that should be <i>rejected</i> into a loop that <i>crashes</i>. The consequence for
     * anyone writing a new guardrail: comparing a possibly-{@code NaN} value with {@code >} or {@code <}
     * is always false, so check {@link Double#isFinite(double)} before you compare, or your term reports
     * that a broken run is fine.
     *
     * <p><b>An RLOG is delta-encoded, and that changes what "per frame" means here.</b> PsiKit writes a
     * field only on the cycles its value changed, and its replay decoder applies those changes to one
     * running table -- so a decoded frame is a <i>snapshot of the whole robot at that cycle</i>, not a list
     * of what was written during it. A field therefore persists, at its last value, in every later frame.
     * Two consequences, both verified against the decoder in
     * {@code org.horizon36596.simloop.sim.RlogDecodedCompare#decode} rather than assumed:
     * <ul>
     *   <li>This list has one entry per frame from the key's first appearance to the end of the run. It is
     *       shorter than {@link #frameCount()} only when the key started being logged mid-run.</li>
     *   <li>A candidate cannot hide a still-moving actuator by ceasing to log it. "Not written this cycle"
     *       decodes as "unchanged", which is exactly what it would have written anyway.</li>
     * </ul>
     *
     * @param key the RLOG key to read
     * @return every value logged under {@code key}, in frame order, in whatever unit the key was logged
     */
    public List<Double> metric(String key) {
        if (!keys.contains(key)) {
            throw new MetricNotFoundException(key, this);
        }
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            String raw = frames.get(i).get(key);
            if (raw == null) {
                // Key exists somewhere in the run but not in this frame. PsiKit only writes a field on
                // the cycles it was recorded, so this is normal for anything logged conditionally.
                continue;
            }
            try {
                values.add(Double.parseDouble(raw.trim()));
            } catch (NumberFormatException e) {
                throw new MetricNotNumericException(key, i, raw, this);
            }
        }
        if (values.isEmpty()) {
            throw new MetricNotFoundException(key, this);
        }
        return Collections.unmodifiableList(values);
    }

    /**
     * The value under {@code key} in the run's last frame. Same failure rules as {@link #metric(String)}.
     *
     * <p><b>This really is the value the run ended on</b>, and that is a property of the RLOG format
     * rather than of this method -- see {@link #metric(String)} on delta encoding. It is worth stating
     * because the obvious worry is wrong in a reassuring direction: a candidate cannot make a still-driving
     * actuator look stopped by quietly ceasing to log it, since in a delta-encoded log "not written this
     * cycle" means "unchanged", not "absent". Ceasing to log is identical to re-logging the same number.
     *
     * @param key the RLOG key to read
     * @return the last value logged under {@code key}, in that key's own unit
     */
    public double finalValue(String key) {
        List<Double> values = metric(key);
        return values.get(values.size() - 1);
    }

    /**
     * The largest value logged under {@code key}. Same failure rules as {@link #metric(String)}.
     *
     * @param key the RLOG key to read
     * @return the maximum, in that key's own unit
     */
    public double maxValue(String key) {
        return metric(key).stream().mapToDouble(Double::doubleValue).max().getAsDouble();
    }

    /**
     * The smallest value logged under {@code key}. Same failure rules as {@link #metric(String)}.
     *
     * @param key the RLOG key to read
     * @return the minimum, in that key's own unit
     */
    public double minValue(String key) {
        return metric(key).stream().mapToDouble(Double::doubleValue).min().getAsDouble();
    }

    /**
     * The mean of every value logged under {@code key}. Same failure rules as {@link #metric(String)}.
     *
     * @param key the RLOG key to read
     * @return the arithmetic mean, in that key's own unit
     */
    public double meanValue(String key) {
        return metric(key).stream().mapToDouble(Double::doubleValue).average().getAsDouble();
    }

    /**
     * True if any frame logged a non-finite number under any key — {@code NaN} or an infinity.
     *
     * <p>Envelope term from {@code scorer-interface.md} §3, and one of the few that genuinely is a log
     * fact. See {@link #nonFiniteKeys()} for exactly how far it can see.
     *
     * @return true when at least one key logged a non-finite number
     */
    public boolean hasNonFiniteValue() {
        return !nonFiniteKeys().isEmpty();
    }

    /**
     * Sorted names of every key that logged a {@code NaN} or an infinity in at least one frame.
     *
     * <p><b>Why a token scan and not {@code Double.parseDouble} on the whole value.</b> §3's term is
     * "no {@code NaN}/{@code Inf} in <i>pose</i>, velocity, or any control output", and a pose is
     * routinely logged as one array-valued field rather than three scalars. Parsing the whole rendered
     * value would decide {@code "[1.0, NaN, 3.0]"} is "not a number at all" and skip it — which is the
     * envelope term failing silently on precisely the field it names. So this looks for {@code NaN} /
     * {@code Infinity} / {@code -Infinity} as standalone tokens anywhere in the rendered value, which is
     * how Java renders a non-finite double in every context, a lone scalar and an array element alike.
     * The token boundaries keep a string field such as {@code "NaNoScale"} from reading as a bad number.
     *
     * <p><b>What it still cannot see, said out loud.</b> A field PsiKit encodes as an opaque struct or a
     * raw byte array does not render its numbers as text at all, so a {@code NaN} buried inside one is
     * invisible here and this method will not claim otherwise. That is survivable today because the
     * logging contract (rules 2–3) requires the scalar {@code measured<Unit>} fields alongside any such
     * struct — {@code Drive} logs {@code measuredXInches} next to its {@code pose2d} — and the scalars
     * are checkable. It is written down rather than left implicit because an envelope term whose blind
     * spot nobody recorded is how an envelope quietly stops guarding.
     *
     * @return a sorted, possibly empty set of the keys that logged a non-finite number
     */
    public Set<String> nonFiniteKeys() {
        Set<String> bad = new TreeSet<>();
        for (Map<String, String> frame : frames) {
            for (Map.Entry<String, String> entry : frame.entrySet()) {
                String raw = entry.getValue();
                if (raw != null && NON_FINITE_TOKEN.matcher(raw).find()) {
                    bad.add(entry.getKey());
                }
            }
        }
        return bad;
    }

    /** Thrown when a metric names a key the run never logged. */
    public static final class MetricNotFoundException extends RuntimeException {
        MetricNotFoundException(String key, RunResult run) {
            super("no metric '" + key + "' in " + run.rlogPath + " (run '" + run.opModeName + "', "
                    + run.frameCount() + " frames). A missing metric is never scored as zero -- a typo "
                    + "would otherwise read as a legitimately bad run. Closest keys logged: "
                    + run.closestKeys(key) + ". All keys: " + run.keys);
        }
    }

    /** Thrown when a metric's logged value is not a number. */
    public static final class MetricNotNumericException extends RuntimeException {
        MetricNotNumericException(String key, int frameIndex, String rawValue, RunResult run) {
            super("metric '" + key + "' logged the non-numeric value '" + rawValue + "' in frame "
                    + frameIndex + " of " + run.rlogPath + ". This is not caught and defaulted on "
                    + "purpose: a boolean or a state name silently parsed as 0.0 turns a broken metric "
                    + "into a plausible score.");
        }
    }

    /** Up to five logged keys sharing the most leading characters with {@code key}, for error messages. */
    private List<String> closestKeys(String key) {
        List<String> sorted = new ArrayList<>(keys);
        String needle = key.toLowerCase();
        sorted.sort((a, b) -> Integer.compare(
                sharedPrefixLength(b.toLowerCase(), needle), sharedPrefixLength(a.toLowerCase(), needle)));
        return sorted.subList(0, Math.min(5, sorted.size()));
    }

    private static int sharedPrefixLength(String a, String b) {
        int limit = Math.min(a.length(), b.length());
        int shared = 0;
        while (shared < limit && a.charAt(shared) == b.charAt(shared)) {
            shared++;
        }
        return shared;
    }
}
