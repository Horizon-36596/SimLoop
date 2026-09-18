package org.horizon36596.simloop.viz;

/**
 * One segment of a mechanism, as it should be <b>drawn</b> in AdvantageScope. Geometry only — this
 * describes a picture, not a plant. The dynamics of the same segment live in {@code MechanismSimConfig} /
 * {@code Mechanism1DofPlant} (domain R6), and the two are deliberately separate: a drawing that silently
 * carried a time constant would invite someone to tune the robot by editing the picture.
 *
 * <p>Two kinds exist, and they differ in exactly one way — <b>which of the segment's two drawn numbers
 * moves</b>:
 *
 * <table border="1">
 *   <caption>The two joint kinds</caption>
 *   <tr><th>Kind</th><th>Drawn length</th><th>Drawn angle</th><th>Example</th></tr>
 *   <tr><td>{@link Kind#LINEAR}</td><td><b>moves</b> (retracted + extension)</td><td>fixed</td>
 *       <td>a slide stage</td></tr>
 *   <tr><td>{@link Kind#REVOLUTE}</td><td>fixed (the arm's length)</td><td><b>moves</b></td>
 *       <td>an arm pivot</td></tr>
 * </table>
 *
 * <p>That is the whole of the difference, and it is why AdvantageScope can draw a stacked chain at all: a
 * {@code Mechanism2d} ligament carries a length and an angle-relative-to-its-parent, which is precisely a
 * linear joint and a revolute joint (`docs/reference/ascope-mechanism-format.md`).
 *
 * <p><b>Zero pose.</b> Every joint's geometry is stated <b>at the mechanism's zero pose</b> — the pose the
 * mechanism is drawn in in CAD. {@link #getZeroPoseDirection()} is where the segment points there. A
 * revolute joint's motion is then measured as a signed angle away from that direction, and a linear joint
 * riding on a revolute parent keeps pointing along its parent as the parent swings, because
 * {@code Mechanism2d} angles are relative. Nothing in this package applies an offset of its own: if a plant
 * reads zero, the drawing shows the CAD pose (B13v Contract clause 3).
 *
 * <p><b>Frame and units.</b> Directions are unit vectors in the robot-local frame ({@code +X} robot-right,
 * {@code +Y} robot-forward, {@code +Z} up). Lengths are <b>inches</b>; angles are <b>radians</b>. Degrees
 * appear only inside {@link MechanismSketch}, at the AdvantageScope boundary (Contract clause 6).
 *
 * <p><b>Why limits live on the joint.</b> AdvantageScope's {@code Mechanism2d} has no notion of a limit —
 * it draws whatever number it is given, including a physically impossible one. Clamping is therefore the
 * emitter's job, and each subclass carries the two numbers it clamps to (B12 Contract line 2, extended to
 * angles by B13v Contract clause 2).
 */
public abstract class JointDefinition {

    /**
     * Which of a segment's two drawn numbers this joint moves. {@link MechanismSketch} switches on this
     * rather than calling a polymorphic "apply yourself" method, so a reader can see both cases side by
     * side in one place instead of chasing them through subclasses (CLAUDE.md rule 12).
     */
    public enum Kind {
        /** The segment's drawn <b>length</b> moves; its angle is fixed. A slide stage. */
        LINEAR,
        /** The segment's drawn <b>angle</b> moves; its length is fixed. An arm pivot. */
        REVOLUTE
    }

    private final String name;
    private final RobotVector3 zeroPoseDirection;
    private final String colorHex;
    private final double lineWeightPixels;

    /**
     * Validates and stores the four things every joint has, whatever kind it is.
     *
     * @param name              the segment's name. Becomes a log-key segment under the mechanism, so keep
     *                          it short and free of {@code /}.
     * @param zeroPoseDirection unit direction the segment points at the mechanism's zero pose, robot-local
     *                          frame. Must already be unit length — see {@link RobotVector3#normalized()}.
     * @param colorHex          stroke colour as {@code "#RRGGBB"}.
     * @param lineWeightPixels  stroke thickness in pixels, {@code > 0}.
     * @throws IllegalArgumentException if any argument is null, blank, non-finite, or out of range.
     */
    protected JointDefinition(
            String name, RobotVector3 zeroPoseDirection, String colorHex, double lineWeightPixels) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("joint name must be a non-blank string");
        }
        if (name.contains("/")) {
            // The name becomes one segment of an AdvantageScope log key; a slash would fabricate an extra
            // level of nesting and the segment would vanish from the tab rather than fail loudly.
            throw new IllegalArgumentException("joint name must not contain '/', got \"" + name + "\"");
        }
        if (zeroPoseDirection == null) {
            throw new IllegalArgumentException("joint \"" + name + "\" needs a zero-pose direction");
        }
        if (!zeroPoseDirection.isUnitLength()) {
            throw new IllegalArgumentException(
                    "joint \"" + name + "\" zero-pose direction must be unit length, got "
                            + zeroPoseDirection + " (length " + zeroPoseDirection.length()
                            + "); call RobotVector3.normalized()");
        }
        if (colorHex == null || !colorHex.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException(
                    "joint \"" + name + "\" color must be \"#RRGGBB\", got " + colorHex);
        }
        if (!Double.isFinite(lineWeightPixels) || lineWeightPixels <= 0.0) {
            throw new IllegalArgumentException(
                    "joint \"" + name + "\" lineWeightPixels must be finite and > 0, got "
                            + lineWeightPixels);
        }
        this.name = name;
        this.zeroPoseDirection = zeroPoseDirection;
        this.colorHex = colorHex.toUpperCase();
        this.lineWeightPixels = lineWeightPixels;
    }

    /** {@return which of the segment's two drawn numbers this joint moves} */
    public abstract Kind getKind();

    /**
     * The segment's drawn length at the zero pose (in). Constant for a {@link Kind#REVOLUTE} joint; for a
     * {@link Kind#LINEAR} one this is only the starting value, and {@link MechanismSketch} grows it as the
     * stage extends.
     *
     * @return the drawn length at the zero pose, in inches
     */
    public abstract double getZeroPoseLengthInches();

    /** {@return the segment's name; one segment of its AdvantageScope log key} */
    public final String getName() {
        return name;
    }

    /**
     * Unit direction the segment points at the mechanism's <b>zero pose</b>, robot-local frame. For a
     * linear joint this is also its travel axis, since a slide stage extends along itself.
     *
     * @return a unit direction in the robot frame; unitless
     */
    public final RobotVector3 getZeroPoseDirection() {
        return zeroPoseDirection;
    }

    /** {@return the stroke colour, {@code "#RRGGBB"}, upper-case} */
    public final String getColorHex() {
        return colorHex;
    }

    /** {@return the stroke thickness in pixels} */
    public final double getLineWeightPixels() {
        return lineWeightPixels;
    }

    /**
     * Shared validation helper: rejects a non-finite number with a message naming the joint and the field,
     * so a bad CAD export says which number it was.
     *
     * @param value     the number to check
     * @param field     the field's name, for the message
     * @param jointName the joint's name, for the message
     * @return {@code value}, unchanged, when it is finite
     */
    protected static double requireFinite(double value, String field, String jointName) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "joint \"" + jointName + "\" " + field + " must be finite, got " + value);
        }
        return value;
    }
}
