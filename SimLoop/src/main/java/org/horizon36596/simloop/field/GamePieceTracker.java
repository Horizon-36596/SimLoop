package org.horizon36596.simloop.field;

import org.psilynx.psikit.core.Logger;
import org.psilynx.psikit.core.wpi.math.Translation3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one place a game piece changes hands. Holds every piece in the sim, decides when a loose piece
 * becomes held, enforces how many the robot can carry, puts ejected pieces back on the field where the
 * caller says, and logs every one of those transitions so a replay shows the same sequence (domain R5).
 *
 * <p><b>What this models, and what it refuses to.</b> The human ruled on 2026-09-03 that game pieces in
 * this sim are <b>possession events plus trigger volumes</b> — not simulated balls. So a piece here has a
 * position, a size, and a state; it does not roll, bounce, jam, get knocked out by another robot, or push
 * back on the mechanism that grabbed it. That is domain R6 holding: no rigid-body or contact physics. This
 * class is bookkeeping over geometry, and it needs no physics to be honest about what an intake does —
 * a mechanism that is running, with a piece inside its mouth, and room left to carry it, gets the piece.
 * (This is what {@code docs/specs/intake.md} §10 assumption #5 was waiting on.)
 *
 * <p><b>The loop, once per sim tick.</b> Two calls bracket a tick, and both are needed:
 * <pre>
 *   tracker.updateRobotPose(driveX, driveY, driveHeading);   // top of the tick: carry held pieces along
 *   tracker.tryAcquire(intakeVolume, intake.isRunning());    // whenever a mechanism could pick up
 *   ... eject(...) / score(...) as the robot releases pieces ...
 *   tracker.record("GamePieces");                            // bottom of the tick: flush the log
 * </pre>
 * {@link #record(String)} is what writes the accumulated transitions into the RLOG and clears them, so a
 * tick without it would carry its transitions into the next tick's log entry. Skipping it entirely is a
 * caller bug this class refuses to let slide: it throws once the unlogged backlog is implausibly large,
 * rather than growing forever and quietly losing the replay record.
 *
 * <p><b>Frame and units.</b> Every position in and out of this class is in the repo's FTC-Cartesian field
 * frame — +X right, +Y forward, origin at the centre of the field — in inches, and headings are radians
 * CCW-positive (conventions §7). Nothing is clamped to the field boundary here: the core does not know
 * how big the field is, and a piece placed off the field is a caller mistake worth seeing in the log
 * rather than one worth silently correcting.
 *
 * <p><b>Determinism (domain R5).</b> There is no clock in this class at all. Pieces are scanned in id
 * order, so a mouth over two pieces with only one slot left always takes the lower-numbered one; the
 * transition log is built from ids, state names and mechanism names with no formatted numbers in it, so
 * no locale can change a logged string; and {@link TriggerVolume} does its trigonometry in
 * {@link StrictMath}. Two identical runs produce identical logs.
 *
 * <p><b>Season-agnostic (domain R7).</b> Nothing here names a game, a piece type, or a subsystem. The
 * piece size and the carrying capacity are numbers the season layer in {@code TeamCode} supplies.
 */
public final class GamePieceTracker {

    /**
     * How many transitions may pile up unlogged before {@link #record(String)} being missing is treated as
     * a defect. A real tick produces at most a handful (one per piece acquired or released), so reaching
     * this many means nobody is calling {@code record} and the replay record is being lost.
     *
     * <p><b>A backstop, not a per-tick check.</b> The tracker has no notion of a tick — that is what keeps
     * it clock-free (domain R5) — so it cannot tell "record was not called this tick" from "nothing
     * changed hands this tick". A short scenario that produces only a few transitions and never logs them
     * will therefore finish without tripping this. What catches that case is a test asserting the backlog
     * is empty at the end of a run, which is what {@code GamePieceSimTest} does; this constant only stops
     * a long run from growing the list forever.
     */
    private static final int MAX_UNLOGGED_TRANSITIONS = 256;

    /**
     * Inches to metres, applied only where a WPI geometry struct is handed to PsiKit.
     *
     * <p>AdvantageScope's 2-D and 3-D Field tabs draw in the field's own coordinate space, which is
     * <b>metres</b> — the same space {@code Drive/pose2d} is logged in, converted for exactly this reason
     * at {@code Drive.java}'s own write boundary ({@code docs/reference/ascope-mechanism-format.md} "the
     * drawing must be in METRES"; {@code MechanismSketch} does the same). A geometry struct handed over in
     * inches renders about 39x too far out, i.e. off the field entirely, with no error anywhere.
     *
     * <p>So this class's whole API stays in inches, and the conversion happens here at the last possible
     * moment and nowhere else.
     */
    private static final double METRES_PER_INCH = 0.0254;

    private final int heldCapacity;
    private final List<GamePiece> pieces = new ArrayList<>();
    private final List<String> unloggedTransitions = new ArrayList<>();

    private double robotXInches;
    private double robotYInches;
    private double robotHeadingRad;
    private boolean robotPoseKnown;

    /**
     * Builds a tracker with no pieces on the field yet.
     *
     * @param heldCapacity the most pieces the robot may hold at once — the season's number (domain R7).
     *                     {@link #tryAcquire} will not take a piece that would push the held count past
     *                     it, which is the sim's stand-in for a full intake refusing another ball.
     * @throws IllegalArgumentException if {@code heldCapacity} is negative. Zero is allowed and means a
     *                                  robot that cannot carry anything, which is a legitimate scenario
     *                                  (drive-only tests) rather than a mistake.
     */
    public GamePieceTracker(int heldCapacity) {
        if (heldCapacity < 0) {
            throw new IllegalArgumentException("heldCapacity must be >= 0, got " + heldCapacity);
        }
        this.heldCapacity = heldCapacity;
    }

    /**
     * Put a new piece on the field, {@link PossessionState#LOOSE}, and return its id.
     *
     * <p>Ids are handed out in call order starting at {@code 0}, and acquisition scans in that order, so
     * the order pieces are added is part of what makes a scenario reproducible (domain R5).
     *
     * @param fieldXInches  field position, inches (+X right)
     * @param fieldYInches  field position, inches (+Y forward)
     * @param radiusInches  the piece's radius, inches — half its diameter (1.4 for a 2.8 in ball). May be
     *                      0 for a point-sized piece, in which case only its centre can trigger a volume.
     * @return the new piece's id
     * @throws IllegalArgumentException if any argument is non-finite or the radius is negative
     */
    public int addLoosePiece(double fieldXInches, double fieldYInches, double radiusInches) {
        requireFinite(fieldXInches, "fieldXInches");
        requireFinite(fieldYInches, "fieldYInches");
        requireFinite(radiusInches, "radiusInches");
        if (radiusInches < 0.0) {
            throw new IllegalArgumentException("radiusInches must be >= 0, got " + radiusInches);
        }
        int id = pieces.size();
        pieces.add(new GamePiece(id, fieldXInches, fieldYInches, radiusInches));
        return id;
    }

    /**
     * Record where the robot is for this tick and carry every held piece to it.
     *
     * <p>A held piece's field position is simply the robot's field position: the model does not know where
     * inside the robot a piece sits, because tracking that would need the contact physics R6 rules out.
     * That is also why {@link #eject} makes the caller state a position — the tracker cannot work out
     * where a released piece landed, and will not pretend to.
     *
     * <p>Call this once per tick before {@link #tryAcquire}, which tests against the pose stored here.
     *
     * @param robotXInches    robot centre, field frame, inches (+X right)
     * @param robotYInches    robot centre, field frame, inches (+Y forward)
     * @param robotHeadingRad robot heading, radians CCW-positive (conventions §7)
     * @throws IllegalArgumentException if any argument is non-finite
     */
    public void updateRobotPose(double robotXInches, double robotYInches, double robotHeadingRad) {
        requireFinite(robotXInches, "robotXInches");
        requireFinite(robotYInches, "robotYInches");
        requireFinite(robotHeadingRad, "robotHeadingRad");
        this.robotXInches = robotXInches;
        this.robotYInches = robotYInches;
        this.robotHeadingRad = robotHeadingRad;
        this.robotPoseKnown = true;
        for (GamePiece piece : pieces) {
            if (piece.isHeld()) {
                piece.setFieldPositionInches(robotXInches, robotYInches);
            }
        }
    }

    /**
     * Take every loose piece that {@code volume} overlaps, up to the remaining capacity — but only while
     * {@code mechanismRunning} is true.
     *
     * <p>Those three conditions together are the whole acquisition rule: <b>a loose piece becomes held
     * only when a trigger volume attached to the robot overlaps it while the acquiring mechanism reports
     * itself running, and only if there is room.</b> A mouth parked over a ball with the roller stopped
     * takes nothing, which is what makes this a model of an intake rather than a magnet.
     *
     * <p>{@link PossessionState#SCORED} pieces are ignored — scored is terminal.
     *
     * @param volume           the mouth to test, in robot-frame inches; its name goes into the log
     * @param mechanismRunning what the acquiring mechanism reports about itself this tick (e.g. the
     *                         subsystem's own {@code isRunning()}); false means nothing is acquired
     * @return how many pieces were newly acquired by this call (0 when the mechanism is off, the mouth is
     *         empty, or the robot is already full)
     * @throws IllegalStateException    if {@link #updateRobotPose} has never been called — testing a
     *                                  robot-mounted volume against an unknown pose would silently use
     *                                  field centre and acquire the wrong pieces
     * @throws IllegalArgumentException if {@code volume} is null
     */
    public int tryAcquire(TriggerVolume volume, boolean mechanismRunning) {
        if (volume == null) {
            throw new IllegalArgumentException("tryAcquire needs a trigger volume");
        }
        if (!robotPoseKnown) {
            throw new IllegalStateException(
                    "call updateRobotPose(...) before tryAcquire(...) — a trigger volume is attached to the"
                            + " robot, so without a pose there is nothing to attach it to");
        }
        if (!mechanismRunning) {
            return 0;
        }

        int acquired = 0;
        for (GamePiece piece : pieces) {      // id order: reproducible fill order (domain R5)
            if (getHeldCount() >= heldCapacity) {
                break;                        // full — the capacity invariant, enforced before each take
            }
            if (piece.getState() != PossessionState.LOOSE) {
                continue;
            }
            boolean overlapping = volume.overlapsPiece(robotXInches, robotYInches, robotHeadingRad,
                    piece.getFieldXInches(), piece.getFieldYInches(), piece.getRadiusInches());
            if (!overlapping) {
                continue;
            }
            transition(piece, PossessionState.HELD, volume.getName());
            piece.setFieldPositionInches(robotXInches, robotYInches);
            acquired++;
        }
        return acquired;
    }

    /**
     * Release a held piece back onto the field at a position the caller states, leaving it
     * {@link PossessionState#LOOSE} and acquirable again.
     *
     * <p>The caller states the position because the tracker cannot know it: where a released piece lands
     * depends on how the mechanism was aimed and how fast it was moving, which is exactly the physics R6
     * rules out. A subsystem that ejects out of the front of the robot computes the spot it wants (usually
     * the robot pose offset along its forward direction) and passes it here.
     *
     * @param pieceId      the piece to release, from {@link #addLoosePiece}
     * @param causeName    a short non-blank label for the log, e.g. {@code "intake"} or {@code "deposit"}
     * @param fieldXInches where it lands, field frame, inches
     * @param fieldYInches where it lands, field frame, inches
     * @throws IllegalArgumentException if the id is unknown, the label is blank, or a position is
     *                                  non-finite
     * @throws IllegalStateException    if that piece is not currently held — ejecting something the robot
     *                                  does not have is a caller bug, not a no-op
     */
    public void eject(int pieceId, String causeName, double fieldXInches, double fieldYInches) {
        GamePiece piece = requirePiece(pieceId);
        String cause = requireCauseName(causeName);
        requireFinite(fieldXInches, "fieldXInches");
        requireFinite(fieldYInches, "fieldYInches");
        requireHeld(piece, "eject");
        transition(piece, PossessionState.LOOSE, cause);
        piece.setFieldPositionInches(fieldXInches, fieldYInches);
    }

    /**
     * Release a held piece into a scoring location, leaving it {@link PossessionState#SCORED} and out of
     * play for the rest of the run.
     *
     * <p>No position is asked for, and the piece keeps the last one it had (the robot's). A scored piece
     * is not on the field any more, so where it sits is not a field position the sim has any use for —
     * {@link #getScoredCount()} is the number that matters.
     *
     * @param pieceId   the piece to score, from {@link #addLoosePiece}
     * @param causeName a short non-blank label for the log, e.g. {@code "deposit"}
     * @throws IllegalArgumentException if the id is unknown or the label is blank
     * @throws IllegalStateException    if that piece is not currently held
     */
    public void score(int pieceId, String causeName) {
        GamePiece piece = requirePiece(pieceId);
        String cause = requireCauseName(causeName);
        requireHeld(piece, "score");
        transition(piece, PossessionState.SCORED, cause);
    }

    /**
     * Publish this tick's possession state to the RLOG under {@code keyPrefix}, then clear the transition
     * backlog. Call once per tick, after every {@code tryAcquire} / {@code eject} / {@code score}.
     *
     * <p>Keys written (the season layer passes {@code "GamePieces"}, giving the keys documented in
     * {@code docs/process/logging-contract.md}):
     * <ul>
     *   <li>{@code <prefix>/heldCount}, {@code <prefix>/looseCount}, {@code <prefix>/scoredCount} — ints</li>
     *   <li>{@code <prefix>/atCapacity} — boolean, true while the robot cannot take another piece</li>
     *   <li>{@code <prefix>/transitions} — one string per transition since the last call, in the order they
     *       happened, e.g. {@code "3 LOOSE->HELD intake"}. Empty on a tick where nothing changed hands,
     *       which is most ticks.</li>
     *   <li>{@code <prefix>/loosePositions3d} — the loose pieces as field-frame points for
     *       AdvantageScope's <b>3D Field</b> tab, in <b>metres</b> like every other geometry struct this
     *       repo logs (see {@link #METRES_PER_INCH}; the key has no unit suffix for the same reason
     *       {@code Drive/pose2d} does not). Held pieces are left out because they are all at the robot
     *       pose already logged as {@code Drive/pose2d}, and scored pieces are out of play.
     *
     *       <p><b>Why {@code Translation3d} and not {@code Translation2d}.</b> Only the 3D Field tab can
     *       draw a game piece as the game's actual piece model, and its <i>Game Piece</i> object accepts
     *       3-D types only — verified against AdvantageScope v26's own source, where
     *       {@code Field3dController_Config.ts} declares
     *       {@code sourceTypes: ["Pose3d", "Pose3d[]", "Transform3d", "Transform3d[]", "Translation3d",
     *       "Translation3d[]"]}. A {@code Translation2d[]} is accepted on both Field tabs only by
     *       <i>Trajectory</i> (which joins the points into one polyline) and <i>Heatmap</i>, so it can
     *       never render as separate balls. The z of each point is the piece's own radius, putting the
     *       ball's centre one radius above the floor so it rests on it.</li>
     * </ul>
     *
     * @param keyPrefix the log key prefix, e.g. {@code "GamePieces"} — must be non-blank
     * @throws IllegalArgumentException if {@code keyPrefix} is null or blank
     */
    public void record(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("game-piece log key prefix must be a non-blank string");
        }
        String prefix = keyPrefix.trim();

        int held = 0;
        int loose = 0;
        int scored = 0;
        for (GamePiece piece : pieces) {
            switch (piece.getState()) {
                case HELD:
                    held++;
                    break;
                case LOOSE:
                    loose++;
                    break;
                case SCORED:
                    scored++;
                    break;
                default:
                    throw new IllegalStateException("unhandled possession state " + piece.getState());
            }
        }

        // Metres, not inches — see METRES_PER_INCH. This is the only place the conversion happens.
        Translation3d[] loosePositions = new Translation3d[loose];
        int next = 0;
        for (GamePiece piece : pieces) {
            if (piece.getState() == PossessionState.LOOSE) {
                // z lifts the ball's centre one radius off the floor so the model rests on it.
                loosePositions[next++] = new Translation3d(
                        piece.getFieldXInches() * METRES_PER_INCH,
                        piece.getFieldYInches() * METRES_PER_INCH,
                        piece.getRadiusInches() * METRES_PER_INCH);
            }
        }

        Logger.recordOutput(prefix + "/heldCount", held);
        Logger.recordOutput(prefix + "/looseCount", loose);
        Logger.recordOutput(prefix + "/scoredCount", scored);
        Logger.recordOutput(prefix + "/atCapacity", held >= heldCapacity);
        Logger.recordOutput(prefix + "/transitions",
                unloggedTransitions.toArray(new String[0]));
        Logger.recordOutput(prefix + "/loosePositions3d", loosePositions);

        unloggedTransitions.clear();
    }

    /** {@return the most pieces the robot may hold at once, as declared at construction} */
    public int getHeldCapacity() {
        return heldCapacity;
    }

    /**
     * How many pieces the robot is carrying right now. Never greater than {@link #getHeldCapacity()}.
     *
     * @return how many pieces the robot is carrying right now
     */
    public int getHeldCount() {
        return countInState(PossessionState.HELD);
    }

    /** {@return how many pieces are on the field, available to acquire} */
    public int getLooseCount() {
        return countInState(PossessionState.LOOSE);
    }

    /** {@return how many pieces have been scored and are out of play} */
    public int getScoredCount() {
        return countInState(PossessionState.SCORED);
    }

    /** {@return true while the robot cannot take another piece} */
    public boolean isAtCapacity() {
        return getHeldCount() >= heldCapacity;
    }

    /**
     * Every piece added so far, in id order.
     *
     * <p><b>Live views, not snapshots.</b> A caller cannot change a piece — {@link GamePiece}'s setters
     * are package-private, and the list itself is unmodifiable — but a piece it holds on to will change
     * position and state underneath it as the tracker updates them. That is deliberate: a scenario asking
     * "where is piece 3 now" wants now, not the answer from the tick it first looked. Read what you need
     * off a piece in the same tick you got it; do not cache a position across ticks and assume it is still
     * true.
     *
     * @return an unmodifiable live view of every piece on the field, in registration order
     */
    public List<GamePiece> getPieces() {
        return Collections.unmodifiableList(pieces);
    }

    /**
     * One piece by id.
     *
     * @param pieceId the id the piece was registered under
     * @return the piece, as a live view (see {@link #getPieces()})
     * @throws IllegalArgumentException if no piece has that id
     */
    public GamePiece getPiece(int pieceId) {
        return requirePiece(pieceId);
    }

    /**
     * The transitions that have happened since the last {@link #record(String)}, in order — the exact
     * strings that call will log.
     *
     * <p><b>A diagnostic surface, not part of the possession model.</b> Nothing a robot does should depend
     * on it: the log is the channel this data is meant to travel on. It is public because a test wants to
     * assert on the transition sequence without decoding an RLOG, and because the Phase-3 loop will want
     * to read the same sequence live rather than after the fact. Calling {@link #record(String)} empties
     * it, so read it before you log, not after.
     *
     * @return an unmodifiable view of the pending transition strings, in the order they happened
     */
    public List<String> getUnloggedTransitions() {
        return Collections.unmodifiableList(unloggedTransitions);
    }

    // Apply a state change and queue its log line. Every state change in this class goes through here,
    // which is what makes "every transition is logged" true by construction rather than by discipline.
    private void transition(GamePiece piece, PossessionState to, String causeName) {
        if (unloggedTransitions.size() >= MAX_UNLOGGED_TRANSITIONS) {
            throw new IllegalStateException(MAX_UNLOGGED_TRANSITIONS + " possession transitions have piled"
                    + " up unlogged — record(keyPrefix) is not being called once per tick, so the replay"
                    + " record of who picked up what is being lost");
        }
        // No formatted numbers in this string on purpose: String.format would apply a default locale and a
        // machine with a comma decimal separator would then log different text for the same run (domain
        // R5). Positions land in loosePositions3d instead.
        unloggedTransitions.add(piece.getId() + " " + piece.getState() + "->" + to + " " + causeName);
        piece.setState(to);
    }

    private int countInState(PossessionState state) {
        int count = 0;
        for (GamePiece piece : pieces) {
            if (piece.getState() == state) {
                count++;
            }
        }
        return count;
    }

    private GamePiece requirePiece(int pieceId) {
        if (pieceId < 0 || pieceId >= pieces.size()) {
            throw new IllegalArgumentException("no game piece with id " + pieceId + "; ids run 0.."
                    + (pieces.size() - 1));
        }
        return pieces.get(pieceId);
    }

    private static void requireHeld(GamePiece piece, String action) {
        if (!piece.isHeld()) {
            throw new IllegalStateException("cannot " + action + " game piece " + piece.getId()
                    + ": the robot is not holding it (state is " + piece.getState() + ")");
        }
    }

    private static String requireCauseName(String causeName) {
        if (causeName == null || causeName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "a possession transition needs a non-blank cause name for the log");
        }
        return causeName.trim();
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
    }
}
