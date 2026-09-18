package org.horizon36596.simloop.sim;

import org.horizon36596.simloop.config.SimRobotConfig;

import org.psilynx.psikit.core.LogDataReceiver;
import org.psilynx.psikit.core.LogTable;
import org.psilynx.psikit.core.Logger;
import org.psilynx.psikit.core.rlog.RLOGWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs one headless sim scenario end-to-end: owns the entire PsiKit lifecycle (sim-harness §2-3) and the
 * fixed-timestep tick loop, so a test does not hand-write it (BACKLOG "scenario runner" / area B). Every
 * sim test up to this point (Batch 1.4's {@code DrivetrainSimTest}, Batch 2.2's {@code SlideSimTest})
 * re-typed the same ~20 lines: create the RLOG directory, reset/wire/start PsiKit's {@link Logger}, tick a
 * {@link FakeTimer} inside a {@code periodicBeforeUser()}/{@code periodicAfterUser()} bracket, call
 * {@link Logger#end()}, then re-check by hand that the writer thread didn't overflow or emit nothing. This
 * class is that boilerplate, written once.
 *
 * <p><b>What stays with the caller.</b> This class is season-agnostic core (architecture §3, domain R7): it
 * knows nothing about a specific robot's motors, plants, or subsystems. Building the fake hardware,
 * commanding the robot, advancing its plants, and deciding what to log each tick is scenario-specific
 * behavior the caller supplies as a {@link ScenarioBody}. The {@link SimRobotConfig} parameter is not read by this
 * class at all — it is recorded as PsiKit metadata purely so a human opening the RLOG in AdvantageScope (or
 * a future Phase-3 scorer) can see which robot config a run used, the same way {@code opModeName} is.
 *
 * <p><b>Trustworthy by construction.</b> {@link #run} throws if PsiKit's async writer queue overflowed
 * (BACKLOG B3 — cycles silently dropped) or if the resulting RLOG is missing or empty (BACKLOG B11) — a
 * scenario whose log cannot be trusted for replay is a defect the moment it happens, not something every
 * caller should have to re-check after the fact.
 */
public final class ScenarioRunner {

    private ScenarioRunner() {
    }

    /** One scenario's per-tick behavior: command the robot, advance its fake hardware/plants, log fields. */
    @FunctionalInterface
    public interface ScenarioBody {
        /**
         * Called once per sim tick, after {@link FakeTimer#advance(double)} and
         * {@link Logger#periodicBeforeUser()} and before {@link Logger#periodicAfterUser(long, long)}.
         *
         * @param deltaTimeSeconds the fixed tick step just advanced (same value passed to {@link #run})
         */
        void tick(double deltaTimeSeconds);
    }

    /**
     * Runs {@code ticks} fixed-timestep sim ticks against {@code timer}, writing everything PsiKit logs to
     * {@code rlogPath}.
     *
     * @param opModeName       recorded as PsiKit metadata ("OpMode"), shown in AdvantageScope
     * @param config           the robot's sim config for this scenario; recorded as metadata only (see
     *                         class javadoc) — this class does not read a robot's motors/plants from it
     * @param timer            the deterministic clock (domain R5) this scenario runs on; the caller
     *                         constructs it (and must wire any sim-side infra to it, e.g.
     *                         {@code HeadlessRobotInitInfra}) BEFORE calling this method, since that wiring
     *                         has to happen before the robot under test is built
     * @param ticks            number of sim ticks to run
     * @param deltaTimeSeconds fixed per-tick time step (sim-harness §3); {@code timer} advances by this
     *                         once per tick, before {@code body.tick(deltaTimeSeconds)} runs
     * @param rlogPath         caller-chosen output file, e.g. {@code Paths.get("build", "sim", "run1.rlog")}
     * @param body             the scenario's per-tick behavior (see {@link ScenarioBody})
     * @throws IllegalStateException if the RLOG this run produced cannot be trusted, for any of four
     *                                reasons: PsiKit's async writer queue overflowed (BACKLOG B3); the file
     *                                is missing or empty (BACKLOG B11); it decodes to a different number of
     *                                frames than ticks were run (BACKLOG B32); or the writer thread stopped
     *                                making progress while the producer waited for it. All four are the same
     *                                statement - the log is not a record of the run - and none of them is
     *                                affected by what the caller's own trajectory checks found
     */
    public static void run(String opModeName, SimRobotConfig config, FakeTimer timer, int ticks,
            double deltaTimeSeconds, Path rlogPath, ScenarioBody body) {
        Path dir = rlogPath.toAbsolutePath().getParent();
        String fileName = rlogPath.getFileName().toString();
        String baseName = fileName.endsWith(".rlog") ? fileName.substring(0, fileName.length() - 5) : fileName;

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("ScenarioRunner: could not create RLOG directory " + dir, e);
        }

        Logger.reset();
        Logger.setTimeSource(timer::time);
        Logger.recordMetadata("OpMode", opModeName);
        Logger.recordMetadata("SimRobotConfig", config.getClass().getSimpleName());
        // Two-arg ctor: the single-arg form prepends the Android path /sdcard/FIRST/PsiKit/ (jar behaviour);
        // (dir, name) keeps the RLOG in the JVM build dir for AdvantageScope replay.
        Logger.addDataReceiver(new RLOGWriter(dir.toString(), baseName));

        // Added AFTER the writer, so a frame is only counted once the writer has finished with it. That
        // ordering is what makes the count usable as backpressure: see WrittenFrameCounter.
        WrittenFrameCounter written = new WrittenFrameCounter();
        Logger.addDataReceiver(written);

        Logger.start();

        int firstDroppedTick = -1;
        // The tick loop is wrapped so that PsiKit is shut down on EVERY exit path, not only the happy one.
        // Logger is global static state holding an open RLOGWriter file handle; a throw out of the scenario
        // body or out of the pacer used to leave that handle open until some later Logger.reset() happened
        // to call end() for us. See the catch below for why the original exception is the one that escapes.
        try {
            for (int i = 0; i < ticks; i++) {
                timer.advance(deltaTimeSeconds);
                Logger.periodicBeforeUser();
                body.tick(deltaTimeSeconds);
                Logger.periodicAfterUser(0, 0);

                // Latch the writer fault OURSELVES, every tick, because PsiKit's flag does not latch: every
                // successful enqueue resets it to false. So a queue that overflows in the middle of a run and
                // then recovers reports "no fault" at the end while the frames it refused are gone for good.
                // Reading the flag once after the loop - which is what this used to do - therefore only ever
                // catches a run whose very LAST tick also overflowed. See BACKLOG B32 for the disassembly.
                if (firstDroppedTick < 0 && Logger.getReceiverQueueFault()) {
                    firstDroppedTick = i;
                }

                paceProducerToWriter(i + 1, written);
            }
        } catch (RuntimeException | Error failureDuringTheRun) {
            // Close the writer before the exception leaves, and let the ORIGINAL failure be the one the
            // caller sees: a shutdown that fails on top of an already-failing run is a consequence, not the
            // cause, and replacing the first exception with the second is how a real bug gets misdiagnosed.
            // The shutdown failure is still readable, as a suppressed exception on the original.
            //
            // One case this CANNOT fix, and it is worth knowing about: if PsiKit's receiver thread has
            // already died (an unchecked throw out of RLOGWriter.putTable kills it - its only handler
            // rethrows), then Logger.end()'s join returns at once and nothing is left alive to call the
            // writer's end(). The file handle stays open until the JVM exits. There is no PsiKit API that
            // reports a dead receiver thread, so this is recorded rather than handled (BACKLOG B34).
            try {
                Logger.end();
            } catch (RuntimeException | Error alsoFailedToShutDown) {
                failureDuringTheRun.addSuppressed(alsoFailedToShutDown);
            }
            throw failureDuringTheRun;
        }

        Logger.end();

        if (firstDroppedTick >= 0) {
            throw new IllegalStateException("ScenarioRunner: PsiKit's async RLOG-writer queue overflowed "
                    + "during '" + opModeName + "', first at tick " + firstDroppedTick + " of " + ticks
                    + " -- cycles were silently dropped, so " + rlogPath + " is not trustworthy regardless"
                    + " of what it decodes to (BACKLOG B3, B32).");
        }
        long size;
        try {
            size = Files.exists(rlogPath) ? Files.size(rlogPath) : 0L;
        } catch (IOException e) {
            throw new UncheckedIOException("ScenarioRunner: could not read RLOG size at " + rlogPath, e);
        }
        if (size == 0) {
            throw new IllegalStateException("ScenarioRunner: '" + opModeName + "' wrote no bytes to " + rlogPath
                    + " -- PsiKit's async writer thread emitted nothing (BACKLOG B11).");
        }

        requireOneDecodedFramePerTick(opModeName, rlogPath, ticks);
    }

    /**
     * How far the producer may get ahead of the writer before it waits, in frames.
     *
     * <p>PsiKit's receiver queue holds 500 and refuses anything past that, so the budget has to sit
     * comfortably under 500 rather than at it: the check below happens once per tick, and the writer can
     * lose ground between two checks. 300 leaves room for that without making the producer wait often.
     */
    private static final int MAX_FRAMES_AHEAD_OF_WRITER = 300;

    /**
     * How many one-millisecond waits the producer will spend on a single frame before it gives up, and
     * declares the writer stalled rather than waiting forever.
     *
     * <p>Counting sleeps rather than reading an elapsed time keeps this method clock-free (domain R5). A
     * {@code sleep(1)} lasts at least a millisecond and usually a little more, so 30,000 of them is at least
     * thirty seconds and probably rather more - far beyond any healthy writer, and short enough that a build
     * fails with a sentence explaining itself instead of hanging until somebody kills it.
     */
    private static final int MAX_WAITS_FOR_ONE_FRAME = 30_000;

    /**
     * Waits, if the writer has fallen too far behind, until it has caught up.
     *
     * <p><b>Why a simulator needs this and the real robot does not.</b> PsiKit's receiver queue holds 500
     * frames and its capacity is a hard-coded {@code private static final} with no setter. On the robot the
     * producer is a 50 Hz control loop, so the writer gets 20 ms per frame and the queue never fills. Here
     * the producer is a {@code for} loop with no clock in it at all: a tick costs whatever the scenario body
     * costs and nothing more, while the writer has to encode each frame and put it on disk. The producer
     * wins that race outright - not because it is scheduled unfairly, but because it is doing less work - so
     * the queue fills, {@code BlockingQueue.add} throws, and PsiKit drops frames. That is how a 1500-tick
     * run comes back holding 1312 of them.
     *
     * <p><b>Why this measures rather than guesses.</b> {@link Thread#yield()} was tried first and is not
     * enough; nor is any fixed sleep, which is either too slow for every run or too small for the worst one.
     * The queue's own depth is not reachable through PsiKit's API - but the receiver list is, so
     * {@link WrittenFrameCounter} is registered behind the writer and counts frames the writer has actually
     * finished. Ticks produced minus frames written is the depth.
     *
     * <p><b>Exactly the depth, until something is dropped.</b> A frame PsiKit refuses is never handed to any
     * receiver, so the counter can never account for it and the difference stays permanently inflated by the
     * number lost. The figure is therefore exact while the queue is healthy and an over-estimate once it is
     * not - which is the safe direction to be wrong in, since an over-estimate only makes the producer wait
     * more. It does mean this loop must not be the only thing standing between a stalled writer and a hung
     * build, which is what {@link #MAX_WAITS_FOR_ONE_FRAME} is for.
     *
     * <p><b>This does not affect determinism (domain R5).</b> Nothing in the sim reads a wall clock - sim
     * time is {@link FakeTimer}, advanced by a fixed step by the loop above - so how long a tick takes in
     * real seconds changes nothing about what is computed or logged. It changes only whether the frame
     * survives the trip to disk. The waiting is bounded by a count of sleeps rather than by an elapsed-time
     * reading, so this method does not read a clock either.
     *
     * @param ticksProduced how many ticks have been handed to the logger so far
     * @param written       the counter sitting behind the RLOG writer
     * @throws IllegalStateException if the writer stops making progress, or if the thread running the
     *                               scenario is interrupted while waiting - in both cases the log this run
     *                               would produce cannot be trusted, and hanging or quietly carrying on
     *                               unpaced are both worse than saying so
     */
    private static void paceProducerToWriter(int ticksProduced, WrittenFrameCounter written) {
        int waits = 0;
        while (ticksProduced - written.count() > MAX_FRAMES_AHEAD_OF_WRITER) {
            if (waits++ >= MAX_WAITS_FOR_ONE_FRAME) {
                throw new IllegalStateException("ScenarioRunner: the RLOG writer stopped making progress at "
                        + "tick " + ticksProduced + " -- it has finished " + written.count() + " frames and "
                        + "has not finished another in " + MAX_WAITS_FOR_ONE_FRAME + " waits. Something is "
                        + "wrong with the writer thread or the disk; the run is abandoned rather than hung.");
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException interrupted) {
                // Restore the flag for whoever is unwinding us, then stop. Returning instead would be worse
                // than it looks: the flag stays set, so EVERY later Thread.sleep in this run throws at once
                // and the pacing is silently off for the rest of the run - the exact failure this class was
                // written to remove, reintroduced by the error handling meant to tolerate it.
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ScenarioRunner: interrupted at tick " + ticksProduced
                        + " while waiting for the RLOG writer to catch up; the run cannot be completed.",
                        interrupted);
            }
        }
    }

    /**
     * A {@link LogDataReceiver} that writes nothing and only counts.
     *
     * <p>It exists so the producer can see how far behind the RLOG writer is. PsiKit does not expose its
     * receiver queue's depth, but it does let a caller add receivers, and it hands every frame to each
     * receiver in turn - so a counter registered AFTER the writer increments only once the writer has
     * finished with that frame. Ticks produced minus this count is the queue depth.
     *
     * <p><b>That last sentence rests on PsiKit internals, so it was checked rather than assumed</b> (the
     * standing rule after BACKLOG B17). Disassembled from the pinned {@code core-0.2.0-beta2} jar:
     * {@code Logger} holds ONE static {@code receiverQueue}, and hands it to a single {@code ReceiverThread};
     * that thread holds the receivers in a {@code java.util.List} and walks it by index, calling
     * {@code putTable} on each in registration order, synchronously, on itself. There is no per-receiver
     * queue and no parallel dispatch, so a no-op counter cannot race ahead of the writer it sits behind.
     * {@code Logger.reset()} - which {@link #run} calls before wiring anything - constructs a brand-new
     * {@code ReceiverThread} with an empty receiver list and clears the queue, so receivers from an earlier
     * run in the same JVM cannot still be attached and inflate this count.
     *
     * <p>All of that is internal to a pinned version and none of it is a published contract. If PsiKit is
     * ever bumped, re-check it: if dispatch became parallel or per-receiver, this counter would drain
     * instantly, the pacing would measure nothing, and the truncation would come back quietly. The
     * completeness check in {@link #run} is the backstop that would still catch it.
     *
     * <p>The count is read from the producer thread and written from the receiver thread, which is why it
     * is an {@code AtomicInteger} rather than an {@code int}.
     */
    private static final class WrittenFrameCounter implements LogDataReceiver {
        private final AtomicInteger framesWritten = new AtomicInteger();

        @Override
        public void putTable(LogTable table) {
            framesWritten.incrementAndGet();
        }

        /** Frames the writer ahead of this one has finished. */
        int count() {
            return framesWritten.get();
        }
    }

    /**
     * Decodes the file that was just written and requires exactly one frame per tick.
     *
     * <p><b>Why the two guards above are not enough.</b> They check that the writer did not report a fault
     * and that it produced some bytes. Neither one says the log is COMPLETE, and an incomplete log is the
     * failure that actually costs something: a run can finish, pass both guards, and still decode to fewer
     * frames than it ran ticks, with the missing frames always at the tail. Measured in a season repo at
     * roughly one full suite run in three - 1484 of 1500 on one scenario, 596 of 600 on another - with
     * {@code Logger.getReceiverQueueFault()} false both times (BACKLOG B32).
     *
     * <p><b>Why this is worth a decode on every run.</b> Everything downstream treats an RLOG as the record
     * of what happened. {@code RunResult.isComplete()} is exact equality on frame count, so a truncated log
     * makes the loop's {@code runIsComplete} guardrail VIOLATE for a control law that did nothing wrong -
     * and the breakdown a human reads is then indistinguishable from a real regression. A scenario scored
     * on the ticks it happened to survive is also the cheapest way there is to win any loop exam. Turning a
     * silently wrong answer into a loud failure is worth more than the milliseconds the decode costs.
     *
     * <p><b>This does not fix the truncation, and is not meant to.</b> The cause is not established - see
     * B32, which has the disassembly of PsiKit's {@code Logger.end()} and {@code ReceiverThread.run()}
     * showing that both the normal and the interrupted path do drain the queue and do close the receivers,
     * so the obvious explanation is ruled out and the real one is still open. What this guarantees is
     * narrower and is the part callers depend on: if {@code run} returns, the file at {@code rlogPath} has
     * every tick in it.
     */
    private static void requireOneDecodedFramePerTick(String opModeName, Path rlogPath, int ticks) {
        int decodedFrames;
        try {
            decodedFrames = RlogDecodedCompare.decode(rlogPath).size();
        } catch (IOException e) {
            throw new UncheckedIOException("ScenarioRunner: '" + opModeName + "' wrote " + rlogPath
                    + " but it could not be decoded back, so there is no way to tell whether the run is"
                    + " complete", e);
        }
        if (decodedFrames != ticks) {
            throw new IllegalStateException("ScenarioRunner: '" + opModeName + "' ran " + ticks
                    + " ticks but " + rlogPath + " decodes to " + decodedFrames + " frames"
                    + (decodedFrames < ticks ? " -- the log is truncated" : " -- the log has extra frames")
                    + ". PsiKit reported no writer fault, so this is BACKLOG B32. The run is NOT scoreable:"
                    + " anything measured from this file is measured over the ticks that survived, not the"
                    + " ticks that ran. Re-run it; it is deterministic, so a clean run is the same run.");
        }
    }
}
