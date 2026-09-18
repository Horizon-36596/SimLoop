package org.horizon36596.simloop.viz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The complete description of one mechanism as AdvantageScope should draw it: a canvas, a mount point, and
 * an ordered chain of telescoping stages. This is the in-code form of the {@code mechanism.json} IR
 * (`docs/reference/mechanism-ir.md`) — the season layer builds one of these, usually from a class generated
 * out of CAD by the {@code /cad-to-mechanism} skill.
 *
 * <p><b>Why the canvas axes are stored rather than derived.</b> AdvantageScope's Mechanism tab is 2-D, so a
 * 3-D robot-local mechanism has to be projected onto a plane. Deriving that plane from the travel direction
 * works until the slide is exactly vertical, at which point the horizontal axis is undefined and any choice
 * is a silent guess. Storing both axes explicitly means the exporter — which can see the whole CAD — makes
 * the decision once, and the emitter is a pure projection with nothing to get wrong (CLAUDE.md rule 12).
 *
 * <p><b>Frame and units.</b> Robot-local: {@code +X} robot-right, {@code +Y} robot-forward, {@code +Z} up,
 * origin at the centre of the drivetrain footprint at floor level. All lengths <b>inches</b>. The canvas is
 * unitless in AdvantageScope, and this repo draws it in inches so the numbers on screen are the numbers in
 * CAD.
 */
public final class MechanismSketchDefinition {

    private final String name;
    private final double canvasWidthInches;
    private final double canvasHeightInches;
    private final String backgroundColorHex;
    private final RobotVector3 canvasHorizontalAxis;
    private final RobotVector3 canvasVerticalAxis;
    private final RobotVector3 mountPointInches;
    private final List<JointDefinition> joints;

    private MechanismSketchDefinition(Builder builder) {
        this.name = builder.name;
        this.canvasWidthInches = builder.canvasWidthInches;
        this.canvasHeightInches = builder.canvasHeightInches;
        this.backgroundColorHex = builder.backgroundColorHex;
        this.canvasHorizontalAxis = builder.canvasHorizontalAxis;
        this.canvasVerticalAxis = builder.canvasVerticalAxis;
        this.mountPointInches = builder.mountPointInches;
        this.joints = Collections.unmodifiableList(new ArrayList<JointDefinition>(builder.joints));
    }

    /**
     * Mechanism name. Becomes the root of its AdvantageScope log key.
     *
     * @return the name; never null or blank
     */
    public String getName() {
        return name;
    }

    /**
     * Canvas width. Must contain the drawn mechanism or it renders clipped.
     *
     * @return the width in inches; positive
     */
    public double getCanvasWidthInches() {
        return canvasWidthInches;
    }

    /**
     * Canvas height. Must contain the fully-extended mechanism, not just the retracted one.
     *
     * @return the height in inches; positive
     */
    public double getCanvasHeightInches() {
        return canvasHeightInches;
    }

    /**
     * Canvas background.
     *
     * @return the colour as {@code "#RRGGBB"}; never null
     */
    public String getBackgroundColorHex() {
        return backgroundColorHex;
    }

    /**
     * Unit robot-local direction drawn as canvas {@code +X} (to the right of the picture).
     *
     * @return a unit direction in the robot frame; unitless
     */
    public RobotVector3 getCanvasHorizontalAxis() {
        return canvasHorizontalAxis;
    }

    /**
     * Unit robot-local direction drawn as canvas {@code +Y} (up the picture). Normally world {@code +Z}.
     *
     * @return a unit direction in the robot frame; unitless
     */
    public RobotVector3 getCanvasVerticalAxis() {
        return canvasVerticalAxis;
    }

    /**
     * Where the mechanism attaches to the robot. Becomes the drawn root.
     *
     * @return the mount point in inches, in the robot frame
     */
    public RobotVector3 getMountPointInches() {
        return mountPointInches;
    }

    /**
     * Every joint, base first. Joint {@code i+1} is drawn attached to the tip of joint {@code i}, so this
     * list <b>is</b> the chain: mixing a {@link RevoluteJointDefinition} and a
     * {@link LinearJointDefinition} here is what "stacked degrees of freedom" means.
     *
     * @return an unmodifiable list of joints, base first
     */
    public List<JointDefinition> getJoints() {
        return joints;
    }

    /**
     * Only the {@link LinearJointDefinition} joints, in chain order — the stages that <em>extend</em>. A
     * mixed chain's travel numbers are about these and not about its pivots, so the two are separated here
     * rather than at every call site.
     *
     * @return an unmodifiable list of the linear stages, in chain order; empty when the chain is all pivots
     */
    public List<LinearJointDefinition> getLinearJoints() {
        List<LinearJointDefinition> linearJoints = new ArrayList<LinearJointDefinition>();
        for (JointDefinition joint : joints) {
            // Kind, not instanceof — JointDefinition says its Kind is how callers tell the two apart, and
            // one file quietly using a different test is how the two drift apart.
            if (joint.getKind() == JointDefinition.Kind.LINEAR) {
                linearJoints.add((LinearJointDefinition) joint);
            }
        }
        return Collections.unmodifiableList(linearJoints);
    }

    /**
     * Sum of every <b>linear</b> stage's {@code maxTravelInches} — the mechanism's full stroke capacity
     * (in), i.e. the CAD travel. Pivots contribute nothing: a revolute joint has a stroke measured in
     * radians, and adding it here would produce a number in no unit at all. Distinct from
     * {@link MechanismSketch#getTotalTravelInches()}, which reads a live sketch's <em>current</em>
     * extension — the two share a topic but not a value, so they are named differently on purpose.
     *
     * @return the summed stroke capacity in inches; zero when the chain has no linear stage
     */
    public double getMaxTotalTravelInches() {
        double total = 0.0;
        for (LinearJointDefinition joint : getLinearJoints()) {
            total += joint.getMaxTravelInches();
        }
        return total;
    }

    /**
     * Start building. See {@link Builder} for what is required.
     *
     * @param name the mechanism name, which becomes the root of its AdvantageScope log key
     * @return a builder with nothing set but the name
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * Step-by-step builder for a {@link MechanismSketchDefinition}. Every value is required except the
     * background colour; {@link #build()} fails loudly rather than defaulting anything that would change
     * what gets drawn.
     */
    public static final class Builder {

        private final String name;
        private final List<JointDefinition> joints = new ArrayList<JointDefinition>();
        private double canvasWidthInches = Double.NaN;
        private double canvasHeightInches = Double.NaN;
        private String backgroundColorHex = "#000000";
        private RobotVector3 canvasHorizontalAxis;
        private RobotVector3 canvasVerticalAxis;
        private RobotVector3 mountPointInches;

        private Builder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("mechanism name must be a non-blank string");
            }
            if (name.contains("/")) {
                throw new IllegalArgumentException(
                        "mechanism name must not contain '/', got \"" + name + "\"");
            }
            this.name = name;
        }

        /**
         * Canvas extents. Both must be positive and should contain the extended mechanism.
         *
         * @param widthInches  canvas width in inches
         * @param heightInches canvas height in inches
         * @return this builder
         */
        public Builder canvasSizeInches(double widthInches, double heightInches) {
            this.canvasWidthInches = widthInches;
            this.canvasHeightInches = heightInches;
            return this;
        }

        /**
         * Canvas background. Defaults to black.
         *
         * @param backgroundColorHex the colour as {@code "#RRGGBB"}
         * @return this builder
         */
        public Builder backgroundColorHex(String backgroundColorHex) {
            this.backgroundColorHex = backgroundColorHex;
            return this;
        }

        /**
         * The projection plane: which robot-local directions become canvas right and canvas up. They must
         * both be unit length and perpendicular to each other.
         *
         * <p><b>This choice also decides a setting a human must make in AdvantageScope's 3D Field tab</b>,
         * and nothing checks that the two agree. AdvantageScope's robot frame is WPILib's — <b>+X forward,
         * +Y left, +Z up</b> — while this repo's is <b>+X right, +Y forward, +Z up</b>. So a canvas of
         * robot-forward x up is AdvantageScope's <b>XZ</b> plane, and a canvas of robot-right x up is its
         * <b>YZ</b> plane. Pick the wrong one in the tab and the mechanism leans out the side of the robot.
         *
         * <p><b>Canvas coordinates are not robot-relative offsets.</b> AdvantageScope centres the robot's
         * origin on the canvas's <em>bottom edge</em>, so the robot sits at canvas {@code (width/2, 0)} and
         * {@code MechanismSketch} shifts the drawing right by half the width to match. Two things follow:
         * the canvas must be at least <b>twice</b> the mechanism's furthest horizontal reach, and canvas
         * coordinates stay positive, so the same log key also renders correctly in the 2-D Mechanism tab.
         *
         * @param horizontalAxis unit robot-local direction drawn as canvas {@code +X}; unitless
         * @param verticalAxis   unit robot-local direction drawn as canvas {@code +Y}; unitless
         * @return this builder
         */
        public Builder canvasAxes(RobotVector3 horizontalAxis, RobotVector3 verticalAxis) {
            this.canvasHorizontalAxis = horizontalAxis;
            this.canvasVerticalAxis = verticalAxis;
            return this;
        }

        /**
         * Where the mechanism attaches to the robot.
         *
         * @param mountPointInches the mount point in inches, in the robot frame
         * @return this builder
         */
        public Builder mountPointInches(RobotVector3 mountPointInches) {
            this.mountPointInches = mountPointInches;
            return this;
        }

        /**
         * Append one joint. Order matters: the first added is the base joint, and each later one is drawn
         * attached to the tip of the one before it. Kinds may be mixed freely — an arm pivot with a slide
         * riding on it is two {@code addJoint} calls, pivot first.
         *
         * @param joint the joint to append; its name must be unique within this mechanism
         * @return this builder
         */
        public Builder addJoint(JointDefinition joint) {
            if (joint == null) {
                throw new IllegalArgumentException("cannot add a null joint to \"" + name + "\"");
            }
            for (JointDefinition existing : joints) {
                if (existing.getName().equals(joint.getName())) {
                    // Duplicate names would collide as log keys: the second stage would overwrite the
                    // first's subtable and the mechanism would silently lose a stage.
                    throw new IllegalArgumentException(
                            "mechanism \"" + name + "\" already has a joint named \"" + joint.getName()
                                    + "\"");
                }
            }
            joints.add(joint);
            return this;
        }

        /**
         * Validates everything and produces the immutable definition.
         *
         * @return the definition, ready to hand to {@link MechanismSketch}
         * @throws IllegalArgumentException if anything required is missing or inconsistent — a bad canvas
         *                                  size, non-unit or non-perpendicular axes, no mount point, or no
         *                                  joints.
         */
        public MechanismSketchDefinition build() {
            if (!Double.isFinite(canvasWidthInches) || !Double.isFinite(canvasHeightInches)
                    || canvasWidthInches <= 0.0 || canvasHeightInches <= 0.0) {
                throw new IllegalArgumentException(
                        "mechanism \"" + name + "\" needs a positive canvas size, got "
                                + canvasWidthInches + " x " + canvasHeightInches
                                + " — call canvasSizeInches(width, height)");
            }
            if (backgroundColorHex == null || !backgroundColorHex.matches("#[0-9a-fA-F]{6}")) {
                throw new IllegalArgumentException(
                        "mechanism \"" + name + "\" backgroundColor must be \"#RRGGBB\", got "
                                + backgroundColorHex);
            }
            if (canvasHorizontalAxis == null || canvasVerticalAxis == null) {
                throw new IllegalArgumentException(
                        "mechanism \"" + name + "\" needs both canvas axes — call canvasAxes(horizontal,"
                                + " vertical). They are stored, not derived, because a vertical mechanism"
                                + " has no derivable horizontal axis.");
            }
            if (!canvasHorizontalAxis.isUnitLength() || !canvasVerticalAxis.isUnitLength()) {
                throw new IllegalArgumentException(
                        "mechanism \"" + name + "\" canvas axes must be unit length, got horizontal "
                                + canvasHorizontalAxis + " and vertical " + canvasVerticalAxis);
            }
            if (!canvasHorizontalAxis.isPerpendicularTo(canvasVerticalAxis)) {
                throw new IllegalArgumentException(
                        "mechanism \"" + name + "\" canvas axes must be perpendicular, got horizontal "
                                + canvasHorizontalAxis + " and vertical " + canvasVerticalAxis
                                + " (dot " + canvasHorizontalAxis.dot(canvasVerticalAxis)
                                + ") — a non-perpendicular pair shears the drawing");
            }
            if (mountPointInches == null) {
                throw new IllegalArgumentException(
                        "mechanism \"" + name + "\" needs a mount point — call mountPointInches(...)");
            }
            if (joints.isEmpty()) {
                throw new IllegalArgumentException(
                        "mechanism \"" + name + "\" needs at least one joint — a mechanism with no stages"
                                + " draws nothing");
            }
            return new MechanismSketchDefinition(this);
        }
    }
}
