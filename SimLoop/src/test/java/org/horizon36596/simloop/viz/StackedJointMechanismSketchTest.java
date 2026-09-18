package org.horizon36596.simloop.viz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MechanismSketch} with <b>stacked degrees of freedom</b> — a chain that mixes a
 * {@link RevoluteJointDefinition} and a {@link LinearJointDefinition}. The single-kind cascade lives in
 * {@link MechanismSketchTest}; this file exists because the stacked case has its own fixture and its own
 * invariants, not because the emitter is two different things.
 *
 * <p>Split by tier (domain R9):
 *
 * <ul>
 *   <li><b>INVARIANT-tier</b> — the B13v Contract, which holds under any redesign and may never be
 *       retired: (1) each segment's drawn length and angle equals the CAD number at that state, the chain
 *       composes to the right tip, and a flipped rotation axis reverses the sweep; (2) a joint commanded
 *       past a hard stop draws at the stop; (3) plant zero draws the CAD pose; (5) a yaw joint is rejected
 *       rather than drawn flat. Plus deterministic redraw (R5), and the rule that input inside the
 *       documented slop is still drawable.</li>
 *   <li><b>IMPLEMENTATION-tier</b> — pins this design's specific choices: that a linear stage riding on a
 *       pivot is stored at relative angle zero, the fail-safe handling of non-finite input, the
 *       kind-mismatch messages, and the guards on splitting travel across a chain that has none.</li>
 * </ul>
 *
 * <p>Two fixtures, both synthetic and deliberately <em>not</em> tied to any robot's CAD, because the core's
 * invariants must not be breakable by a CAD change:
 * <ul>
 *   <li><b>arm + slide</b> — one pivot drawn 30° above robot-forward with a slide riding on it. The
 *       smallest chain that stacks.</li>
 *   <li><b>lift + shoulder + elbow + wrist</b> — the shapes the small one cannot reach: a pivot that is not
 *       first, two pivots in series with opposite rotation axes, and a stage carried by a pivot.</li>
 * </ul>
 */
class StackedJointMechanismSketchTest {

    /** Canvas is the robot's side view: right is robot-forward (+Y), up is world up (+Z). */
    private static final RobotVector3 CANVAS_HORIZONTAL = new RobotVector3(0.0, 1.0, 0.0);
    private static final RobotVector3 CANVAS_VERTICAL = new RobotVector3(0.0, 0.0, 1.0);

    /**
     * The axis an arm pitches about on a side-view canvas: robot-right, which is exactly
     * {@code CANVAS_HORIZONTAL × CANVAS_VERTICAL}. A positive angle about it raises the arm.
     */
    private static final RobotVector3 PITCH_AXIS = new RobotVector3(1.0, 0.0, 0.0);

    /** The axis a turret yaws about — straight up, which no side-view canvas can show. */
    private static final RobotVector3 YAW_AXIS = new RobotVector3(0.0, 0.0, 1.0);

    /** The arm is drawn 30° above robot-forward. That pose is the mechanism's zero. */
    private static final double ARM_ZERO_POSE_DEGREES = 30.0;

    private static final double ARM_LENGTH_INCHES = 10.0;
    private static final double ARM_MIN_ANGLE_DEGREES = -45.0;
    private static final double ARM_MAX_ANGLE_DEGREES = 60.0;

    private static final double SLIDE_RETRACTED_INCHES = 6.0;
    private static final double SLIDE_TRAVEL_INCHES = 12.0;

    /** Four-joint chain: the lift runs straight up; the shoulder and elbow are drawn at these angles. */
    private static final double LIFT_ZERO_POSE_DEGREES = 90.0;
    private static final double SHOULDER_ZERO_POSE_DEGREES = 60.0;
    private static final double ELBOW_ZERO_POSE_DEGREES = 20.0;

    private static final double LIFT_RETRACTED_INCHES = 5.0;
    private static final double SHOULDER_LENGTH_INCHES = 8.0;
    private static final double ELBOW_LENGTH_INCHES = 6.0;
    private static final double WRIST_RETRACTED_INCHES = 3.0;

    /** Mount: on the robot's centreline, 4 in off the floor. */
    private static final RobotVector3 MOUNT = new RobotVector3(0.0, 0.0, 4.0);

    /** Tolerances: 1 mm expressed in inches, and 0.5°, matching the B12 Contract's. */
    private static final double ONE_MILLIMETRE_INCHES = 1.0 / 25.4;
    private static final double HALF_DEGREE = 0.5;

    // ---------------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------------

    /** A unit direction in the canvas plane, {@code degrees} above robot-forward. */
    private static RobotVector3 inCanvasPlaneAt(double degrees) {
        double radians = Math.toRadians(degrees);
        return new RobotVector3(0.0, Math.cos(radians), Math.sin(radians));
    }

    /** The arm pivot on its own — a chain with no linear stage at all. */
    private static MechanismSketchDefinition.Builder armOnly() {
        return MechanismSketchDefinition.builder("Arm")
                .canvasSizeInches(48.0, 48.0)
                .backgroundColorHex("#202020")
                .canvasAxes(CANVAS_HORIZONTAL, CANVAS_VERTICAL)
                .mountPointInches(MOUNT)
                .addJoint(pivot(PITCH_AXIS));
    }

    /** The arm pivot with the slide riding on its tip — the stacked case. */
    private static MechanismSketchDefinition armWithSlide() {
        return armOnly()
                .addJoint(new LinearJointDefinition(
                        "slide",
                        // Drawn along the arm at the zero pose, which is what makes it ride on the arm.
                        inCanvasPlaneAt(ARM_ZERO_POSE_DEGREES),
                        SLIDE_RETRACTED_INCHES,
                        0.0,
                        SLIDE_TRAVEL_INCHES,
                        "#4C9AFF",
                        6.0))
                .build();
    }

    /**
     * A four-joint chain: a vertical lift stage, a shoulder pivot on its tip, an elbow pivot turning the
     * other way, and a wrist stage on the end. Every chain shape the emitter allows appears here — a pivot
     * that is not first, two pivots in series, and a stage carried by a pivot.
     */
    private static MechanismSketchDefinition liftShoulderElbowWrist() {
        return MechanismSketchDefinition.builder("Deposit")
                .canvasSizeInches(96.0, 96.0)
                .backgroundColorHex("#202020")
                .canvasAxes(CANVAS_HORIZONTAL, CANVAS_VERTICAL)
                .mountPointInches(MOUNT)
                .addJoint(new LinearJointDefinition(
                        "lift", inCanvasPlaneAt(LIFT_ZERO_POSE_DEGREES),
                        LIFT_RETRACTED_INCHES, 0.0, 10.0, "#4C9AFF", 6.0))
                .addJoint(new RevoluteJointDefinition(
                        "shoulder", inCanvasPlaneAt(SHOULDER_ZERO_POSE_DEGREES), PITCH_AXIS,
                        SHOULDER_LENGTH_INCHES, Math.toRadians(-90.0), Math.toRadians(90.0),
                        "#FF9F4C", 8.0))
                .addJoint(new RevoluteJointDefinition(
                        // Turns about -X, so a positive command lowers what it carries. The two pivots
                        // disagree on sign on purpose: that is where a sign bug would hide.
                        "elbow", inCanvasPlaneAt(ELBOW_ZERO_POSE_DEGREES),
                        new RobotVector3(-1.0, 0.0, 0.0),
                        ELBOW_LENGTH_INCHES, Math.toRadians(-90.0), Math.toRadians(90.0),
                        "#7FD17F", 8.0))
                .addJoint(new LinearJointDefinition(
                        "wrist", inCanvasPlaneAt(ELBOW_ZERO_POSE_DEGREES),
                        WRIST_RETRACTED_INCHES, 0.0, 6.0, "#D17FD1", 6.0))
                .build();
    }

    private static RevoluteJointDefinition pivot(RobotVector3 rotationAxis) {
        return new RevoluteJointDefinition(
                "pivot",
                inCanvasPlaneAt(ARM_ZERO_POSE_DEGREES),
                rotationAxis,
                ARM_LENGTH_INCHES,
                Math.toRadians(ARM_MIN_ANGLE_DEGREES),
                Math.toRadians(ARM_MAX_ANGLE_DEGREES),
                "#FF9F4C",
                8.0);
    }

    // ---------------------------------------------------------------------------------------------
    // INVARIANT-tier — B13v Contract
    // ---------------------------------------------------------------------------------------------

    /**
     * Contract 3: a plant reading zero draws the pose CAD drew. The arm was drawn 30° above robot-forward,
     * so that is where a fresh sketch puts it — no offset applied anywhere.
     */
    @Test
    void aFreshSketchDrawsThePivotAtTheCadPose() {
        MechanismSketch sketch = new MechanismSketch(armWithSlide());

        assertEquals(0.0, sketch.getJointAngleRadians("pivot"), 1.0e-12,
                "a fresh sketch is at the zero pose by definition");
        assertEquals(ARM_ZERO_POSE_DEGREES, sketch.getDrawnAngleDegrees("pivot"), HALF_DEGREE,
                "the arm should be drawn where CAD drew it, 30 degrees above robot-forward");
        assertEquals(ARM_LENGTH_INCHES, sketch.getDrawnLengthInches("pivot"), ONE_MILLIMETRE_INCHES,
                "an arm's drawn length is its arm length");
    }

    /**
     * Contract 1: commanding the pivot moves its angle by exactly that much, and moves nothing else. The
     * arm swings; it does not grow.
     */
    @Test
    void commandingThePivotMovesItsAngleAndNotItsLength() {
        MechanismSketch sketch = new MechanismSketch(armWithSlide());

        sketch.setJointAngleRadians("pivot", Math.toRadians(20.0));

        assertEquals(Math.toRadians(20.0), sketch.getJointAngleRadians("pivot"), 1.0e-12);
        assertEquals(ARM_ZERO_POSE_DEGREES + 20.0, sketch.getDrawnAngleDegrees("pivot"), HALF_DEGREE,
                "a commanded angle is measured from the CAD pose, so it adds to it");
        assertEquals(ARM_LENGTH_INCHES, sketch.getDrawnLengthInches("pivot"), ONE_MILLIMETRE_INCHES,
                "a pivot has a fixed length at every angle");
    }

    /**
     * Contract 2: a pivot commanded through its own hard stop draws at the stop. {@code Mechanism2d} would
     * happily draw an arm swung through the robot's own frame; clamping is this class's job.
     */
    @Test
    void aPivotCommandedPastItsHardStopDrawsAtTheStop() {
        MechanismSketch sketch = new MechanismSketch(armWithSlide());

        sketch.setJointAngleRadians("pivot", Math.toRadians(ARM_MAX_ANGLE_DEGREES + 500.0));
        assertEquals(ARM_ZERO_POSE_DEGREES + ARM_MAX_ANGLE_DEGREES,
                sketch.getDrawnAngleDegrees("pivot"), HALF_DEGREE,
                "over-commanding upward must stop at the upper limit");

        sketch.setJointAngleRadians("pivot", Math.toRadians(ARM_MIN_ANGLE_DEGREES - 500.0));
        assertEquals(ARM_ZERO_POSE_DEGREES + ARM_MIN_ANGLE_DEGREES,
                sketch.getDrawnAngleDegrees("pivot"), HALF_DEGREE,
                "over-commanding downward must stop at the lower limit");
    }

    /**
     * Contract 1: the slide riding on the arm is stored at relative angle <b>zero</b>, so the renderer
     * carries it around with its parent and nothing here recomputes kinematics.
     *
     * <p><b>What the loop over arm angles is actually for.</b> In this design nothing writes a child's
     * angle, so moving the pivot cannot change the slide's stored angle: the loop is not testing the pivot
     * maths, which is {@link #theChainComposesToTheTipTheGeometrySays()}'s job. It guards one specific
     * wrong fix — someone "helping" a future pivot along by also rotating its children, which would
     * double-transform everything downstream. That regression is easy to write and invisible on screen
     * until a chain has two moving joints.
     */
    @Test
    void theSlideRidingOnTheArmKeepsPointingAlongTheArm() {
        MechanismSketch sketch = new MechanismSketch(armWithSlide());

        assertEquals(0.0, sketch.getDrawnAngleDegrees("slide"), HALF_DEGREE,
                "at the zero pose the slide already points along the arm");

        for (double armDegrees : new double[] {-45.0, -10.0, 0.0, 25.0, 60.0}) {
            sketch.setJointAngleRadians("pivot", Math.toRadians(armDegrees));
            assertEquals(0.0, sketch.getDrawnAngleDegrees("slide"), HALF_DEGREE,
                    "the slide must stay along the arm at arm angle " + armDegrees + " degrees");
        }
    }

    /**
     * Contract 1, end to end: with the arm swung and the slide extended, the chain composes to the tip the
     * geometry says it should. This is the test that would fail if relative angles were being accumulated
     * wrongly, since it walks the drawn ligaments and rebuilds the tip from them.
     */
    @Test
    void theChainComposesToTheTipTheGeometrySays() {
        MechanismSketch sketch = new MechanismSketch(armWithSlide());
        sketch.setJointAngleRadians("pivot", Math.toRadians(20.0));
        sketch.setJointExtensionInches("slide", SLIDE_TRAVEL_INCHES);

        // Both segments end up along the same line — 30 degrees of CAD pose plus 20 commanded — so the tip
        // is simply their combined length in that direction, measured from the mount.
        double armAndSlideDegrees = ARM_ZERO_POSE_DEGREES + 20.0;
        double combinedLengthInches =
                ARM_LENGTH_INCHES + SLIDE_RETRACTED_INCHES + SLIDE_TRAVEL_INCHES;
        double expectedAcrossInches = MOUNT.dot(CANVAS_HORIZONTAL)
                + combinedLengthInches * Math.cos(Math.toRadians(armAndSlideDegrees));
        double expectedUpInches = MOUNT.dot(CANVAS_VERTICAL)
                + combinedLengthInches * Math.sin(Math.toRadians(armAndSlideDegrees));

        assertEquals(expectedAcrossInches, drawnTipAcrossCanvasInches(sketch), ONE_MILLIMETRE_INCHES,
                "chain tip, across the canvas");
        assertEquals(expectedUpInches, drawnTipUpCanvasInches(sketch), ONE_MILLIMETRE_INCHES,
                "chain tip, up the canvas");
    }

    /**
     * Contract 1: the rotation axis is what gives an angle its sign, so flipping it must swing the arm the
     * other way. INVARIANT-tier rather than implementation-tier: there is no redesign in which this sign
     * comes out backwards and the drawn angle still equals the CAD number for that state.
     */
    @Test
    void flippingTheRotationAxisSweepsTheOtherWay() {
        MechanismSketchDefinition flipped = MechanismSketchDefinition.builder("Arm")
                .canvasSizeInches(48.0, 48.0)
                .canvasAxes(CANVAS_HORIZONTAL, CANVAS_VERTICAL)
                .mountPointInches(MOUNT)
                .addJoint(pivot(new RobotVector3(-1.0, 0.0, 0.0)))
                .build();

        MechanismSketch sketch = new MechanismSketch(flipped);
        sketch.setJointAngleRadians("pivot", Math.toRadians(20.0));

        assertEquals(ARM_ZERO_POSE_DEGREES - 20.0, sketch.getDrawnAngleDegrees("pivot"), HALF_DEGREE,
                "a rotation axis pointing against the canvas normal reverses the drawn sweep");
    }

    /**
     * Contract 5: a turret yaw is rejected at construction with a message that names the alternative.
     * AdvantageScope's {@code Mechanism2d} cannot show a yaw at any plane setting — its plane is a fixed
     * viewer setting and a segment carries only a length and an angle — so drawing it flattened would be a
     * picture of a mechanism that does not exist.
     */
    @Test
    void aYawJointIsRejectedRatherThanDrawnFlat() {
        MechanismSketchDefinition yawing = MechanismSketchDefinition.builder("Turret")
                .canvasSizeInches(48.0, 48.0)
                .canvasAxes(CANVAS_HORIZONTAL, CANVAS_VERTICAL)
                .mountPointInches(MOUNT)
                .addJoint(pivot(YAW_AXIS))
                .build();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> new MechanismSketch(yawing));
        assertTrue(thrown.getMessage().contains("yaw"),
                "the message should say what is wrong: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("b13-stacked-dof-viz"),
                "the message should point at the alternatives: " + thrown.getMessage());
    }

    /**
     * R5: the same commands redraw the same picture. Two sketches built from the same definition and driven
     * identically must agree bit for bit, or a replay could not be compared against a previous run.
     */
    @Test
    void redrawingTheSameCommandsIsBitIdentical() {
        MechanismSketch first = new MechanismSketch(armWithSlide());
        MechanismSketch second = new MechanismSketch(armWithSlide());

        for (MechanismSketch sketch : new MechanismSketch[] {first, second}) {
            sketch.setJointAngleRadians("pivot", Math.toRadians(17.3));
            sketch.setJointExtensionInches("slide", 7.9);
        }

        assertEquals(first.getDrawnAngleDegrees("pivot"), second.getDrawnAngleDegrees("pivot"), 0.0,
                "pivot angle must be bit-identical across runs");
        assertEquals(first.getDrawnLengthMetres("slide"), second.getDrawnLengthMetres("slide"), 0.0,
                "slide length must be bit-identical across runs");
    }

    /**
     * Contract 1 on the chain shapes the two-joint fixture cannot reach: a pivot that is <b>not first</b>,
     * two pivots <b>in series</b>, rotation axes that disagree in sign, and a stage carried by a pivot.
     * Those are where an error in accumulating the zero-pose angles would hide, because in the two-joint
     * fixture the parent angle happens to start at zero.
     *
     * <p>The expected tip is worked out here from the CAD constants and the commanded angles alone — it
     * never asks the sketch where anything is — then compared against the tip rebuilt from what was
     * actually drawn.
     */
    @Test
    void aFourJointChainWithTwoPivotsComposesToTheTipTheGeometrySays() {
        MechanismSketch sketch = new MechanismSketch(liftShoulderElbowWrist());

        sketch.setJointExtensionInches("lift", 4.0);
        sketch.setJointAngleRadians("shoulder", Math.toRadians(25.0));
        sketch.setJointAngleRadians("elbow", Math.toRadians(15.0));
        sketch.setJointExtensionInches("wrist", 6.0);

        // Each segment's absolute angle is its own CAD pose plus every commanded rotation at or below it in
        // the chain, signed by that pivot's rotation axis. The shoulder turns about +X, so +25 raises what
        // it carries; the elbow turns about -X, so +15 lowers it.
        double liftDegrees = LIFT_ZERO_POSE_DEGREES;
        double shoulderDegrees = SHOULDER_ZERO_POSE_DEGREES + 25.0;
        double elbowDegrees = ELBOW_ZERO_POSE_DEGREES + 25.0 - 15.0;
        double wristDegrees = elbowDegrees;

        double expectedAcross = MOUNT.dot(CANVAS_HORIZONTAL)
                + (LIFT_RETRACTED_INCHES + 4.0) * Math.cos(Math.toRadians(liftDegrees))
                + SHOULDER_LENGTH_INCHES * Math.cos(Math.toRadians(shoulderDegrees))
                + ELBOW_LENGTH_INCHES * Math.cos(Math.toRadians(elbowDegrees))
                + (WRIST_RETRACTED_INCHES + 6.0) * Math.cos(Math.toRadians(wristDegrees));
        double expectedUp = MOUNT.dot(CANVAS_VERTICAL)
                + (LIFT_RETRACTED_INCHES + 4.0) * Math.sin(Math.toRadians(liftDegrees))
                + SHOULDER_LENGTH_INCHES * Math.sin(Math.toRadians(shoulderDegrees))
                + ELBOW_LENGTH_INCHES * Math.sin(Math.toRadians(elbowDegrees))
                + (WRIST_RETRACTED_INCHES + 6.0) * Math.sin(Math.toRadians(wristDegrees));

        assertEquals(expectedAcross, drawnTipAcrossCanvasInches(sketch), ONE_MILLIMETRE_INCHES,
                "four-joint chain tip, across the canvas");
        assertEquals(expectedUp, drawnTipUpCanvasInches(sketch), ONE_MILLIMETRE_INCHES,
                "four-joint chain tip, up the canvas");
    }

    /**
     * A legal input must not be rejected. Every direction here is unit length only to within the slop
     * {@link RobotVector3#isUnitLength()} documents as acceptable, and those slops multiply when a
     * direction is projected onto two axes — so a tolerance set at the inputs' own tolerance would report
     * this perfectly ordinary arm as an undrawable turret yaw.
     */
    @Test
    void directionsAtTheEdgeOfTheAllowedSlopAreStillDrawable() {
        double edge = 1.0 + 9.0e-7;
        RobotVector3 armDirection = inCanvasPlaneAt(ARM_ZERO_POSE_DEGREES);

        MechanismSketchDefinition definition = MechanismSketchDefinition.builder("Arm")
                .canvasSizeInches(48.0, 48.0)
                .canvasAxes(new RobotVector3(0.0, edge, 0.0), new RobotVector3(0.0, 0.0, edge))
                .mountPointInches(MOUNT)
                .addJoint(new RevoluteJointDefinition(
                        "pivot",
                        new RobotVector3(0.0, armDirection.y * edge, armDirection.z * edge),
                        new RobotVector3(edge, 0.0, 0.0),
                        ARM_LENGTH_INCHES,
                        Math.toRadians(ARM_MIN_ANGLE_DEGREES),
                        Math.toRadians(ARM_MAX_ANGLE_DEGREES),
                        "#FF9F4C",
                        8.0))
                .build();

        MechanismSketch sketch = new MechanismSketch(definition);

        assertEquals(ARM_ZERO_POSE_DEGREES, sketch.getDrawnAngleDegrees("pivot"), HALF_DEGREE,
                "an arm built from directions inside the documented slop must still draw at its CAD pose");
    }

    // ---------------------------------------------------------------------------------------------
    // IMPLEMENTATION-tier — this design's specific choices
    // ---------------------------------------------------------------------------------------------

    /** A bad telemetry read draws the CAD pose rather than aborting the sim. */
    @Test
    void aNonFiniteAngleDrawsTheCadPose() {
        MechanismSketch sketch = new MechanismSketch(armWithSlide());
        sketch.setJointAngleRadians("pivot", Math.toRadians(40.0));

        sketch.setJointAngleRadians("pivot", Double.NaN);

        assertEquals(0.0, sketch.getJointAngleRadians("pivot"), 1.0e-12);
        assertEquals(ARM_ZERO_POSE_DEGREES, sketch.getDrawnAngleDegrees("pivot"), HALF_DEGREE);
    }

    /** Asking a pivot for an extension is a caller mistake, and the message says which method to use. */
    @Test
    void usingTheStageMethodsOnAPivotIsRejected() {
        MechanismSketch sketch = new MechanismSketch(armWithSlide());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> sketch.setJointExtensionInches("pivot", 1.0));
        assertTrue(thrown.getMessage().contains("AngleRadians"),
                "the message should name the right method: " + thrown.getMessage());
    }

    /** And the reverse: asking a stage for an angle. */
    @Test
    void usingThePivotMethodsOnAStageIsRejected() {
        MechanismSketch sketch = new MechanismSketch(armWithSlide());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> sketch.setJointAngleRadians("slide", 1.0));
        assertTrue(thrown.getMessage().contains("ExtensionInches"),
                "the message should name the right method: " + thrown.getMessage());
    }

    /**
     * The cascading-slide helper has nothing to split on a chain of pure pivots. Doing nothing quietly
     * would hide the caller's mistake, so it says so.
     */
    @Test
    void splittingTotalTravelAcrossAPivotOnlyChainIsRejected() {
        MechanismSketch sketch = new MechanismSketch(armOnly().build());

        assertThrows(IllegalStateException.class, () -> sketch.setTotalTravelInches(5.0));
    }

    /** Total travel counts stages only — a pivot's radians have no business in an inches sum. */
    @Test
    void totalTravelIgnoresPivots() {
        MechanismSketchDefinition definition = armWithSlide();

        assertEquals(SLIDE_TRAVEL_INCHES, definition.getMaxTotalTravelInches(), 1.0e-12);
        assertEquals(1, definition.getLinearJoints().size(), "only the slide is a linear stage");
        assertEquals(2, definition.getJoints().size(), "but the chain is two joints long");
    }

    /**
     * {@code getLinearJoints()} keeps chain order and drops only the pivots. The four-joint chain is the
     * only fixture where this can go wrong: it has two stages with two pivots between them.
     */
    @Test
    void theLinearJointsComeBackInChainOrderWithThePivotsDropped() {
        MechanismSketchDefinition definition = liftShoulderElbowWrist();

        assertEquals(4, definition.getJoints().size(), "the chain is four joints long");
        assertEquals(2, definition.getLinearJoints().size(), "two of them are stages");
        assertEquals("lift", definition.getLinearJoints().get(0).getName(), "base stage comes first");
        assertEquals("wrist", definition.getLinearJoints().get(1).getName(), "tip stage comes second");
    }

    /**
     * A chain whose stages have no travel between them cannot have a total split across it. Without this
     * guard the split divides by zero, every stage silently lands at its minimum, and the mechanism looks
     * fine on screen while ignoring every command.
     */
    @Test
    void splittingTotalTravelAcrossStagesWithNoTravelIsRejected() {
        MechanismSketchDefinition noTravel = MechanismSketchDefinition.builder("Slide")
                .canvasSizeInches(48.0, 48.0)
                .canvasAxes(CANVAS_HORIZONTAL, CANVAS_VERTICAL)
                .mountPointInches(MOUNT)
                .addJoint(new LinearJointDefinition(
                        "stage1", inCanvasPlaneAt(90.0), 5.0, -3.0, 0.0, "#4C9AFF", 6.0))
                .build();
        MechanismSketch sketch = new MechanismSketch(noTravel);

        assertThrows(IllegalStateException.class, () -> sketch.setTotalTravelInches(1.0));
    }

    // ---------------------------------------------------------------------------------------------
    // RevoluteJointDefinition validation — each throw branch, R9 guardrails
    // ---------------------------------------------------------------------------------------------

    /**
     * Angles are measured from the pose the mechanism is drawn in, so a range excluding zero says CAD drew
     * a pose the arm cannot reach. That is a modelling mistake, not a number to clamp.
     */
    @Test
    void anAngleRangeThatExcludesTheCadPoseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RevoluteJointDefinition(
                "pivot", inCanvasPlaneAt(30.0), PITCH_AXIS, 10.0,
                Math.toRadians(10.0), Math.toRadians(60.0), "#FF9F4C", 8.0));
    }

    @Test
    void aZeroLengthArmIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RevoluteJointDefinition(
                "pivot", inCanvasPlaneAt(30.0), PITCH_AXIS, 0.0,
                Math.toRadians(-10.0), Math.toRadians(10.0), "#FF9F4C", 8.0));
    }

    @Test
    void anInvertedAngleRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RevoluteJointDefinition(
                "pivot", inCanvasPlaneAt(30.0), PITCH_AXIS, 10.0,
                Math.toRadians(10.0), Math.toRadians(-10.0), "#FF9F4C", 8.0));
    }

    @Test
    void anUnNormalizedRotationAxisIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RevoluteJointDefinition(
                "pivot", inCanvasPlaneAt(30.0), new RobotVector3(2.0, 0.0, 0.0), 10.0,
                Math.toRadians(-10.0), Math.toRadians(10.0), "#FF9F4C", 8.0));
    }

    @Test
    void aMissingRotationAxisIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RevoluteJointDefinition(
                "pivot", inCanvasPlaneAt(30.0), null, 10.0,
                Math.toRadians(-10.0), Math.toRadians(10.0), "#FF9F4C", 8.0));
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers — walk the drawn ligaments back into canvas coordinates
    // ---------------------------------------------------------------------------------------------

    /**
     * The chain's tip, measured across the canvas from the mount (in), rebuilt from what was actually drawn:
     * each segment's drawn length laid off along its accumulated absolute angle.
     */
    private static double drawnTipAcrossCanvasInches(MechanismSketch sketch) {
        double across = MOUNT.dot(CANVAS_HORIZONTAL);
        double absoluteDegrees = 0.0;
        for (JointDefinition joint : sketch.getDefinition().getJoints()) {
            absoluteDegrees += sketch.getDrawnAngleDegrees(joint.getName());
            across += sketch.getDrawnLengthInches(joint.getName())
                    * Math.cos(Math.toRadians(absoluteDegrees));
        }
        return across;
    }

    /** The chain's tip, measured up the canvas from the mount (in). See the across-canvas twin. */
    private static double drawnTipUpCanvasInches(MechanismSketch sketch) {
        double up = MOUNT.dot(CANVAS_VERTICAL);
        double absoluteDegrees = 0.0;
        for (JointDefinition joint : sketch.getDefinition().getJoints()) {
            absoluteDegrees += sketch.getDrawnAngleDegrees(joint.getName());
            up += sketch.getDrawnLengthInches(joint.getName())
                    * Math.sin(Math.toRadians(absoluteDegrees));
        }
        return up;
    }
}
