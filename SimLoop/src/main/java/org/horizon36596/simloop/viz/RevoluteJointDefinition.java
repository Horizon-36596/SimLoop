package org.horizon36596.simloop.viz;

/**
 * One pivot of a mechanism — a segment of <b>fixed length</b> whose <b>drawn angle</b> moves. An arm that
 * swings on a shaft is the case this was written for. See {@link JointDefinition} for what the two joint
 * kinds have in common, the zero-pose convention, and why limits live on the joint.
 *
 * <p><b>Where the numbers will come from in CAD.</b> The Onshape intake feature is a later sub-batch
 * (B13v.3); it is specified to ask for two picks and two numbers
 * (`docs/briefs/b13-stacked-dof-viz.md` §2.1), and they land here directly:
 * <ul>
 *   <li>the <b>pivot centre</b> and the <b>segment tip as drawn</b> give {@link #getArmLengthInches()} (the
 *       distance between them) and the zero-pose direction (the unit vector from pivot to tip);</li>
 *   <li>the <b>minimum</b> and <b>maximum</b> angles are entered as signed numbers, measured from the
 *       as-drawn direction — so a range written {@code [-30°, +90°]} reads the way a human thinks about the
 *       arm.</li>
 * </ul>
 * Nothing here needs a separate "zero reference" pick, because the pose the mechanism is drawn in
 * <em>is</em> zero (B13v Contract clause 3).
 *
 * <p><b>Why a rotation axis is stored.</b> An angle on a plane has no sign until you say which way is
 * positive, and that is exactly what the rotation axis says: by the right-hand rule, a positive angle about
 * {@link #getRotationAxis()} sweeps the arm one way, and about its negation the other. Storing it also
 * makes the one thing this drawing <b>cannot</b> represent detectable rather than silent — a turret yawing
 * about {@code +Z} has a rotation axis that does not stand perpendicular to the canvas plane, so
 * {@link MechanismSketch} rejects it with a message pointing at the alternatives instead of drawing the
 * sweep flattened into the wrong plane (Contract clause 5).
 *
 * <p><b>Angles are radians</b>, per repo convention; degrees appear only inside {@link MechanismSketch}.
 */
public final class RevoluteJointDefinition extends JointDefinition {

    private final RobotVector3 rotationAxis;
    private final double armLengthInches;
    private final double minAngleRadians;
    private final double maxAngleRadians;

    /**
     * Validates and stores one pivoting segment's drawn geometry and its angle range.
     *
     * @param name              the pivot's name. Becomes a log-key segment under the mechanism, so keep it
     *                          short and free of {@code /}.
     * @param zeroPoseDirection unit direction from the pivot to the segment's tip <b>as drawn in CAD</b>,
     *                          robot-local frame. Must already be unit length — see
     *                          {@link RobotVector3#normalized()}.
     * @param rotationAxis      unit axis the segment turns about, robot-local frame; a positive angle
     *                          follows the right-hand rule about it. Must stand perpendicular to the canvas
     *                          plane, which {@link MechanismSketch} checks because only it knows the canvas.
     * @param armLengthInches   distance from the pivot to the segment's tip (in), {@code > 0}. Constant —
     *                          a revolute joint swings, it does not extend.
     * @param minAngleRadians   most negative angle the segment can reach, measured from the zero pose.
     *                          Must be {@code <= 0}, so the CAD pose is reachable.
     * @param maxAngleRadians   most positive angle the segment can reach, measured from the zero pose.
     *                          Must be {@code >= 0}, so the CAD pose is reachable.
     * @param colorHex          stroke colour as {@code "#RRGGBB"}.
     * @param lineWeightPixels  stroke thickness in pixels, {@code > 0}.
     * @throws IllegalArgumentException if any argument is null, blank, non-finite or out of range, if
     *                                  either direction is not unit length, if {@code min >= max}, or if
     *                                  the angle range excludes zero (see below).
     */
    public RevoluteJointDefinition(
            String name,
            RobotVector3 zeroPoseDirection,
            RobotVector3 rotationAxis,
            double armLengthInches,
            double minAngleRadians,
            double maxAngleRadians,
            String colorHex,
            double lineWeightPixels) {
        super(name, zeroPoseDirection, colorHex, lineWeightPixels);
        if (rotationAxis == null) {
            throw new IllegalArgumentException("joint \"" + name + "\" needs a rotation axis");
        }
        if (!rotationAxis.isUnitLength()) {
            throw new IllegalArgumentException(
                    "joint \"" + name + "\" rotation axis must be unit length, got " + rotationAxis
                            + " (length " + rotationAxis.length() + "); call RobotVector3.normalized()");
        }
        double armLength = requireFinite(armLengthInches, "armLengthInches", name);
        double min = requireFinite(minAngleRadians, "minAngleRadians", name);
        double max = requireFinite(maxAngleRadians, "maxAngleRadians", name);
        if (armLength <= 0.0) {
            throw new IllegalArgumentException(
                    "joint \"" + name + "\" armLengthInches must be > 0, got " + armLength
                            + " — a zero-length arm draws nothing and has no direction on screen");
        }
        if (min >= max) {
            throw new IllegalArgumentException(
                    "joint \"" + name + "\" angle range must have min < max, got [" + min + ", " + max
                            + "] rad");
        }
        if (min > 0.0 || max < 0.0) {
            // Angles are measured from the pose the mechanism is drawn in, so a range that excludes zero
            // says the CAD drawing shows a pose the arm cannot reach. Clamping would then draw something
            // other than the CAD pose when a plant reads zero, quietly breaking Contract clause 3 — so this
            // is a modelling mistake to report, not a number to clamp.
            throw new IllegalArgumentException(
                    "joint \"" + name + "\" angle range [" + min + ", " + max + "] rad excludes zero, but"
                            + " angles are measured from the pose the mechanism is DRAWN in, so zero must"
                            + " be reachable. Either the CAD pose or the limits are wrong.");
        }
        this.rotationAxis = rotationAxis;
        this.armLengthInches = armLength;
        this.minAngleRadians = min;
        this.maxAngleRadians = max;
    }

    @Override
    public Kind getKind() {
        return Kind.REVOLUTE;
    }

    /**
     * The arm's length (in) — the same number as {@link #getArmLengthInches()}, under the name every
     * joint kind answers to. Code holding a {@link JointDefinition} calls this one; code that knows it
     * has a pivot should call {@code getArmLengthInches()}, which says what the number actually is.
     */
    @Override
    public double getZeroPoseLengthInches() {
        return armLengthInches;
    }

    /**
     * Unit axis the segment turns about, robot-local. A positive angle follows the right-hand rule about
     * it.
     *
     * @return the rotation axis as a unit vector in the robot frame; unitless
     */
    public RobotVector3 getRotationAxis() {
        return rotationAxis;
    }

    /**
     * Distance from the pivot to the segment's tip (in). Constant at every angle — a revolute joint
     * swings, it does not extend. Same value as {@link #getZeroPoseLengthInches()}, which is the name
     * shared with the other joint kinds.
     *
     * @return the pivot-to-tip distance, in inches
     */
    public double getArmLengthInches() {
        return armLengthInches;
    }

    /**
     * Most negative reachable angle (rad), measured from the zero pose. Always {@code <= 0}.
     *
     * @return most negative reachable angle (rad), measured from the zero pose
     */
    public double getMinAngleRadians() {
        return minAngleRadians;
    }

    /**
     * Most positive reachable angle (rad), measured from the zero pose. Always {@code >= 0}.
     *
     * @return most positive reachable angle (rad), measured from the zero pose
     */
    public double getMaxAngleRadians() {
        return maxAngleRadians;
    }

    /**
     * {@code angleRadians} clamped into {@code [minAngleRadians, maxAngleRadians]}. A non-finite input
     * clamps to <b>zero</b> — the CAD pose — rather than throwing: a bad telemetry read should draw the
     * mechanism at home, not abort the sim (the plant, not the picture, owns command validity). Zero is
     * always in range, because the constructor rejects a range that excludes it.
     */
    double clampAngle(double angleRadians) {
        if (!Double.isFinite(angleRadians)) {
            return 0.0;
        }
        return Math.max(minAngleRadians, Math.min(maxAngleRadians, angleRadians));
    }
}
