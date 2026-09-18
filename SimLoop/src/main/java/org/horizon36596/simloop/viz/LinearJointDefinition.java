package org.horizon36596.simloop.viz;

/**
 * One telescoping stage of a linear mechanism — a segment whose <b>drawn length</b> moves while its angle
 * stays put. A slide stage is the case this was written for. See {@link JointDefinition} for what the two
 * joint kinds have in common, the zero-pose convention, and why limits live on the joint.
 *
 * <p><b>Travel</b> is measured along {@link #getZeroPoseDirection()} from the stage's retracted position,
 * so {@code minTravelInches} is normally {@code 0} and {@code maxTravelInches} is the stroke. For a linear
 * stage the travel axis and the zero-pose direction are the same vector — a slide extends along itself —
 * which is why this class takes only one direction.
 */
public final class LinearJointDefinition extends JointDefinition {

    private final double retractedLengthInches;
    private final double minTravelInches;
    private final double maxTravelInches;

    /**
     * Validates and stores one extending stage's drawn geometry and its travel range.
     *
     * @param name                  the stage's name. Becomes a log-key segment under the mechanism, so keep
     *                              it short and free of {@code /}.
     * @param axis                  unit direction of travel in the robot-local frame, which for a linear
     *                              stage is also the direction it points at the zero pose, so it is read
     *                              back as {@link #getZeroPoseDirection()} — the name every joint kind
     *                              answers to. Must already be unit length; see
     *                              {@link RobotVector3#normalized()}.
     * @param retractedLengthInches drawn length of this stage when fully retracted (in), {@code >= 0}. For a
     *                              cascading slide this is the collapsed length of the stage's own tube.
     * @param minTravelInches       lowest extension along {@code axis} (in). Usually {@code 0}.
     * @param maxTravelInches       highest extension along {@code axis} (in). Must exceed {@code min}.
     * @param colorHex              stroke colour as {@code "#RRGGBB"}.
     * @param lineWeightPixels      stroke thickness in pixels, {@code > 0}.
     * @throws IllegalArgumentException if any argument is null, blank, non-finite, out of range, if
     *                                  {@code axis} is not unit length, or if {@code min >= max}.
     */
    public LinearJointDefinition(
            String name,
            RobotVector3 axis,
            double retractedLengthInches,
            double minTravelInches,
            double maxTravelInches,
            String colorHex,
            double lineWeightPixels) {
        super(name, axis, colorHex, lineWeightPixels);
        double retracted = requireFinite(retractedLengthInches, "retractedLengthInches", name);
        double min = requireFinite(minTravelInches, "minTravelInches", name);
        double max = requireFinite(maxTravelInches, "maxTravelInches", name);
        if (retracted < 0.0) {
            throw new IllegalArgumentException(
                    "joint \"" + name + "\" retractedLengthInches must be >= 0, got " + retracted);
        }
        if (min >= max) {
            throw new IllegalArgumentException(
                    "joint \"" + name + "\" travel must have min < max, got [" + min + ", " + max + "]");
        }
        this.retractedLengthInches = retracted;
        this.minTravelInches = min;
        this.maxTravelInches = max;
    }

    @Override
    public Kind getKind() {
        return Kind.LINEAR;
    }

    /**
     * The stage's drawn length at the zero pose (in): its retracted length plus its clamped minimum travel.
     * {@link MechanismSketch} grows this as the stage extends.
     */
    @Override
    public double getZeroPoseLengthInches() {
        return retractedLengthInches + clampTravel(minTravelInches);
    }

    /** {@return the drawn length when fully retracted (in)} */
    public double getRetractedLengthInches() {
        return retractedLengthInches;
    }

    /** {@return the lowest extension along the travel axis (in)} */
    public double getMinTravelInches() {
        return minTravelInches;
    }

    /** {@return the highest extension along the travel axis (in)} */
    public double getMaxTravelInches() {
        return maxTravelInches;
    }

    /**
     * {@code extensionInches} clamped into {@code [minTravelInches, maxTravelInches]}. A non-finite input
     * clamps to the minimum rather than throwing: a bad telemetry read should draw a retracted stage, not
     * abort the sim (the plant, not the picture, owns command validity).
     */
    double clampTravel(double extensionInches) {
        if (!Double.isFinite(extensionInches)) {
            return minTravelInches;
        }
        return Math.max(minTravelInches, Math.min(maxTravelInches, extensionInches));
    }
}
