package org.horizon36596.simloop.field;

/**
 * A rectangular region bolted to the robot that decides whether a game piece is close enough to be
 * acquired — the "mouth" of an intake, expressed as geometry instead of contact physics (domain R6).
 *
 * <p><b>Why a rectangle on the floor and not a 3-D volume.</b> Acquisition happens where a mechanism
 * meets a piece lying on the field, so the only question this class has to answer is a footprint
 * question: is the piece within the mouth's rectangle. Modelling height would only matter if pieces could
 * be knocked, stacked, or dropped, and R6 rules that class of physics out. The name says "volume" because
 * that is the term the project uses for the concept; the geometry is deliberately 2-D and the sim has no
 * Z axis for pieces.
 *
 * <p><b>Robot frame and units — read this before writing one down.</b> The rectangle is defined in the
 * robot's own frame in inches, using the two directions a person naturally describes a robot with:
 * <ul>
 *   <li><b>forward</b> — the way the robot drives when both sticks go up. {@code forwardOffsetInches} is
 *       positive toward the front bumper, negative toward the back.</li>
 *   <li><b>left</b> — the driver's left when standing behind the robot. {@code leftOffsetInches} is
 *       positive toward the left side, negative toward the right.</li>
 * </ul>
 * {@code depthInches} is the rectangle's extent along forward and {@code widthInches} its extent along
 * left, both full extents rather than half-extents. So an intake mouth spanning the whole front of an
 * 18 in robot, reaching 6 in out ahead of the frame's centre, is
 * {@code new TriggerVolume("intake", 8.0, 0.0, 6.0, 16.0)}.
 *
 * <p><b>Field frame it is tested against.</b> {@link #overlapsPiece} takes the robot's pose in the repo's
 * FTC-Cartesian field frame (+X right, +Y forward, centre origin, inches, heading CCW-positive with the
 * robot's forward direction being {@code (cos heading, sin heading)} — conventions §7), rotates the piece
 * into the robot frame, and does one circle-versus-rectangle test. There is no second field frame and no
 * translation anywhere (conventions §7).
 *
 * <p>Immutable and pure: nothing here holds state, reads a clock, or allocates per call, so the same
 * inputs give the same answer on replay (domain R5) and season-agnostic core stays season-agnostic
 * (domain R7) — this class does not know what a piece is for.
 */
public final class TriggerVolume {

    private final String name;
    private final double forwardOffsetInches;
    private final double leftOffsetInches;
    private final double depthInches;
    private final double widthInches;

    /**
     * Validates and stores one robot-relative rectangle.
     *
     * @param name                a short label used in the possession transition log, e.g. {@code "intake"}.
     *                            Must be non-blank so a logged transition always names what caused it.
     * @param forwardOffsetInches centre of the rectangle along robot forward, inches (+ = toward the front)
     * @param leftOffsetInches    centre of the rectangle along robot left, inches (+ = toward the left side)
     * @param depthInches         full extent along robot forward, inches (&gt; 0)
     * @param widthInches         full extent along robot left, inches (&gt; 0)
     * @throws IllegalArgumentException if the name is null/blank, any number is non-finite, or either
     *                                  extent is not positive (a zero-area mouth can never acquire, which
     *                                  is a configuration mistake rather than a robot that never intakes).
     */
    public TriggerVolume(String name, double forwardOffsetInches, double leftOffsetInches,
            double depthInches, double widthInches) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("trigger volume needs a non-blank name for the log");
        }
        requireFinite(forwardOffsetInches, "forwardOffsetInches");
        requireFinite(leftOffsetInches, "leftOffsetInches");
        requireFinite(depthInches, "depthInches");
        requireFinite(widthInches, "widthInches");
        if (depthInches <= 0.0) {
            throw new IllegalArgumentException("depthInches must be positive, got " + depthInches);
        }
        if (widthInches <= 0.0) {
            throw new IllegalArgumentException("widthInches must be positive, got " + widthInches);
        }
        this.name = name.trim();
        this.forwardOffsetInches = forwardOffsetInches;
        this.leftOffsetInches = leftOffsetInches;
        this.depthInches = depthInches;
        this.widthInches = widthInches;
    }

    /**
     * True when this volume, carried by a robot at the given field pose, overlaps the given piece.
     *
     * <p>The piece is treated as a circle of {@code pieceRadiusInches} about its centre, so "overlap"
     * means the circle touches the rectangle — a ball whose centre is just outside the mouth but whose
     * body is inside it counts, which is what a real intake does. Touching exactly counts as overlapping.
     *
     * @param robotXInches      robot centre, field frame, inches (+X right)
     * @param robotYInches      robot centre, field frame, inches (+Y forward)
     * @param robotHeadingRad   robot heading, radians, CCW-positive; the robot's forward direction is
     *                          {@code (cos, sin)} of this angle (conventions §7)
     * @param pieceXInches      piece centre, field frame, inches
     * @param pieceYInches      piece centre, field frame, inches
     * @param pieceRadiusInches piece radius, inches (must be &ge; 0)
     * @throws IllegalArgumentException if any argument is non-finite or the radius is negative — a NaN
     *                                  pose silently answering "no overlap" would hide a broken plant
     *                                  rather than report it.
     * @return true when the piece's circle overlaps this rectangle at that robot pose
     */
    public boolean overlapsPiece(double robotXInches, double robotYInches, double robotHeadingRad,
            double pieceXInches, double pieceYInches, double pieceRadiusInches) {
        requireFinite(robotXInches, "robotXInches");
        requireFinite(robotYInches, "robotYInches");
        requireFinite(robotHeadingRad, "robotHeadingRad");
        requireFinite(pieceXInches, "pieceXInches");
        requireFinite(pieceYInches, "pieceYInches");
        requireFinite(pieceRadiusInches, "pieceRadiusInches");
        if (pieceRadiusInches < 0.0) {
            throw new IllegalArgumentException("pieceRadiusInches must be >= 0, got " + pieceRadiusInches);
        }

        // Rotate the piece from the field frame into the robot's forward/left frame. StrictMath so the
        // answer is bit-identical on every JVM and replay stays reproducible (domain R5), matching
        // Mechanism1DofPlant's use of StrictMath for the same reason.
        double deltaX = pieceXInches - robotXInches;
        double deltaY = pieceYInches - robotYInches;
        double cos = StrictMath.cos(robotHeadingRad);
        double sin = StrictMath.sin(robotHeadingRad);
        double pieceForwardInches = deltaX * cos + deltaY * sin;
        double pieceLeftInches = -deltaX * sin + deltaY * cos;

        // Circle-versus-rectangle: the shortest distance from the piece's centre to the rectangle, taken
        // one axis at a time, is zero on an axis where the centre already lies inside the rectangle's span.
        double forwardGap = Math.max(0.0,
                Math.abs(pieceForwardInches - forwardOffsetInches) - depthInches / 2.0);
        double leftGap = Math.max(0.0,
                Math.abs(pieceLeftInches - leftOffsetInches) - widthInches / 2.0);

        // Compare squared distances so no square root is needed (and none of its rounding).
        return forwardGap * forwardGap + leftGap * leftGap
                <= pieceRadiusInches * pieceRadiusInches;
    }

    /** {@return the label this volume is logged under when it acquires a piece} */
    public String getName() {
        return name;
    }

    /** {@return the centre of the rectangle along robot forward, inches (+ = toward the front)} */
    public double getForwardOffsetInches() {
        return forwardOffsetInches;
    }

    /** {@return the centre of the rectangle along robot left, inches (+ = toward the left side)} */
    public double getLeftOffsetInches() {
        return leftOffsetInches;
    }

    /** {@return the full extent along robot forward, inches} */
    public double getDepthInches() {
        return depthInches;
    }

    /** {@return the full extent along robot left, inches} */
    public double getWidthInches() {
        return widthInches;
    }

    @Override
    public String toString() {
        return "TriggerVolume(" + name + ")";
    }

    // Fail loud on a non-finite value: NaN comparisons are always false, so a NaN pose would quietly
    // answer "not overlapping" forever and look exactly like a mechanism that never picks anything up.
    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
    }
}
