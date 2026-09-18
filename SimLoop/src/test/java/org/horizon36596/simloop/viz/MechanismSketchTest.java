package org.horizon36596.simloop.viz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MechanismSketch}, split by tier (domain R9):
 *
 * <ul>
 *   <li><b>INVARIANT-tier</b> — the B12 Contract, which holds under any redesign and may never be retired:
 *       (1) the drawn mechanism has the same stage count, total travel, slide angle and mount offset as the
 *       definition it came from, within 1 mm and 0.5°; (2) a stage commanded past its declared travel is
 *       never drawn past its extended endpoint; (4) the retracted pose is the definition's retracted pose,
 *       so encoder zero is the same place in both worlds. Plus deterministic redraw (R5).</li>
 *   <li><b>IMPLEMENTATION-tier</b> — pins this design's specific choices: proportional cascade splitting,
 *       fail-safe handling of non-finite input, rejection of an out-of-plane axis, and the builder's
 *       validation. A different-but-correct emitter honouring the Contract could legitimately fail these.</li>
 * </ul>
 *
 * <p>The fixture is a synthetic <b>4-stage cascade at 10° from vertical</b>, sized like MISUMI SAR230
 * rails. It is deliberately <em>not</em> tied to the season robot's CAD — those numbers are pinned by
 * {@code TeamCode}'s {@code SlideMechanismSketchTest}, and a CAD change must not be able to break the
 * core's own invariants.
 * Each SAR230 is a 300 mm rail with a 180 mm stroke, so the stack is 300 mm collapsed and 1020 mm extended,
 * with 720 mm of travel. Those are the numbers a human can check against the CAD.
 */
class MechanismSketchTest {

    private static final double MM_PER_INCH = 25.4;

    /** SAR230 stroke, 180 mm, in inches — one stage's travel. */
    private static final double STAGE_TRAVEL_INCHES = 180.0 / MM_PER_INCH;

    /** SAR230 rail, 300 mm, in inches — the collapsed height of the whole nested stack. */
    private static final double COLLAPSED_LENGTH_INCHES = 300.0 / MM_PER_INCH;

    private static final int STAGE_COUNT = 4;

    /** Total stroke of the 4-stage stack: 720 mm. */
    private static final double TOTAL_TRAVEL_INCHES = STAGE_COUNT * STAGE_TRAVEL_INCHES;

    /** The slide leans 10° from vertical, toward robot-forward. */
    private static final double LEAN_FROM_VERTICAL_DEGREES = 10.0;

    /** Contract tolerance: 1 mm, expressed in inches. */
    private static final double ONE_MILLIMETRE_INCHES = 1.0 / MM_PER_INCH;

    /** Contract tolerance: 0.5°. */
    private static final double HALF_DEGREE = 0.5;

    /** Mount point: 6 in forward of robot centre, 2 in off the floor. */
    private static final RobotVector3 MOUNT = new RobotVector3(0.0, 6.0, 2.0);

    /** Canvas is the robot's side view: right is robot-forward (+Y), up is world up (+Z). */
    private static final RobotVector3 CANVAS_HORIZONTAL = new RobotVector3(0.0, 1.0, 0.0);
    private static final RobotVector3 CANVAS_VERTICAL = new RobotVector3(0.0, 0.0, 1.0);

    // ---------------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------------

    /**
     * The 4-stage SAR230 slide. Only the base stage has a retracted length: in a cascade the upper stages
     * nest inside the base when collapsed, so drawing them as zero-length until they extend is what the
     * real mechanism looks like.
     */
    private static MechanismSketchDefinition sar230Slide() {
        double leanRadians = Math.toRadians(LEAN_FROM_VERTICAL_DEGREES);
        RobotVector3 travelAxis = new RobotVector3(0.0, Math.sin(leanRadians), Math.cos(leanRadians));

        MechanismSketchDefinition.Builder builder = MechanismSketchDefinition.builder("Slide")
                .canvasSizeInches(24.0, 48.0)
                .backgroundColorHex("#202020")
                .canvasAxes(CANVAS_HORIZONTAL, CANVAS_VERTICAL)
                .mountPointInches(MOUNT);

        for (int stage = 1; stage <= STAGE_COUNT; stage++) {
            builder.addJoint(new LinearJointDefinition(
                    "stage" + stage,
                    travelAxis,
                    stage == 1 ? COLLAPSED_LENGTH_INCHES : 0.0,
                    0.0,
                    STAGE_TRAVEL_INCHES,
                    "#4C9AFF",
                    6.0));
        }
        return builder.build();
    }

    // ---------------------------------------------------------------------------------------------
    // INVARIANT-tier — B12 Contract
    // ---------------------------------------------------------------------------------------------

    /** Contract 1: the drawing has the same stage count as the CAD-derived definition. */
    @Test
    void drawnStageCountMatchesTheDefinition() {
        MechanismSketchDefinition definition = sar230Slide();
        MechanismSketch sketch = new MechanismSketch(definition);

        assertEquals(STAGE_COUNT, definition.getJoints().size(), "definition should hold 4 stages");
        for (int stage = 1; stage <= STAGE_COUNT; stage++) {
            // Throws if the stage is missing, so reaching every one proves all four were drawn.
            sketch.getDrawnLengthInches("stage" + stage);
        }
    }

    /** Contract 1: total travel drawn equals the CAD stroke (720 mm) within 1 mm. */
    @Test
    void totalDrawnTravelMatchesCadStrokeWithinOneMillimetre() {
        MechanismSketch sketch = new MechanismSketch(sar230Slide());

        double retractedTotalLength = totalDrawnLengthInches(sketch);
        sketch.setTotalTravelInches(TOTAL_TRAVEL_INCHES);
        double extendedTotalLength = totalDrawnLengthInches(sketch);

        assertEquals(
                720.0 / MM_PER_INCH,
                extendedTotalLength - retractedTotalLength,
                ONE_MILLIMETRE_INCHES,
                "extending the slide fully should add exactly the CAD stroke of 720 mm");
    }

    /** Contract 1: the slide is drawn at its CAD angle — 10° from vertical is 80° from canvas horizontal. */
    @Test
    void drawnAngleMatchesCadAngleWithinHalfADegree() {
        MechanismSketch sketch = new MechanismSketch(sar230Slide());

        assertEquals(
                90.0 - LEAN_FROM_VERTICAL_DEGREES,
                sketch.getDrawnAngleDegrees("stage1"),
                HALF_DEGREE,
                "base stage should be drawn 80° above canvas horizontal");
        for (int stage = 2; stage <= STAGE_COUNT; stage++) {
            assertEquals(
                    0.0,
                    sketch.getDrawnAngleDegrees("stage" + stage),
                    HALF_DEGREE,
                    "stage " + stage + " is collinear with the stage below, so its RELATIVE angle is 0");
        }
    }

    /** Contract 1: the mechanism mounts where CAD says, projected onto the canvas axes. */
    @Test
    void mountSitsAtTheCadMountPointProjectedOntoTheCanvasAxes() {
        MechanismSketch sketch = new MechanismSketch(sar230Slide());

        assertEquals(6.0, sketch.getMountAcrossRobotInches(), ONE_MILLIMETRE_INCHES,
                "mount should project 6 in along canvas horizontal (robot-forward), signed and physical");
        assertEquals(2.0, sketch.getRootCanvasYInches(), ONE_MILLIMETRE_INCHES,
                "mount should project 2 in up the canvas (world +Z); vertical takes no shift");
    }

    /**
     * INVARIANT: the root written to the log is shifted <b>half a canvas width</b> right of the mount's
     * robot-relative offset.
     *
     * <p>AdvantageScope's 3D Field tab centres the robot's origin on the canvas's bottom edge, so canvas
     * {@code (0, 0)} is the bottom-LEFT corner, not the robot. Writing the raw robot-relative offset draws
     * the whole mechanism half a canvas width away from the robot — floating beside it, which is precisely
     * the bug this shift fixes. If this test fails, the mechanism has come unstuck from the robot.
     */
    @Test
    void theLoggedRootIsShiftedHalfACanvasWidthSoAdvantageScopeLandsItOnTheRobot() {
        MechanismSketch sketch = new MechanismSketch(sar230Slide());

        assertEquals(12.0 + 6.0, sketch.getRootCanvasXInches(), ONE_MILLIMETRE_INCHES,
                "24 in canvas puts the robot origin at canvas x = 12, so a mount 6 in forward is at 18");
        assertEquals(
                sketch.getMountAcrossRobotInches()
                        + sketch.getDefinition().getCanvasWidthInches() / 2.0,
                sketch.getRootCanvasXInches(),
                ONE_MILLIMETRE_INCHES,
                "the shift is exactly half the canvas width, whatever the canvas size");
    }

    /** Contract 2: over-commanding a single stage never draws it past its extended endpoint. */
    @Test
    void aStageCommandedPastItsTravelIsDrawnAtTheStopNotBeyondIt() {
        MechanismSketch sketch = new MechanismSketch(sar230Slide());

        sketch.setJointExtensionInches("stage2", STAGE_TRAVEL_INCHES * 10.0);

        assertEquals(STAGE_TRAVEL_INCHES, sketch.getJointExtensionInches("stage2"), 1.0e-12,
                "a 10x over-command must clamp to the stage's own stroke");
        assertEquals(STAGE_TRAVEL_INCHES, sketch.getDrawnLengthInches("stage2"), 1.0e-12,
                "and the drawn ligament must not exceed it either");
    }

    /** Contract 2: over-commanding the whole mechanism never draws past full extension. */
    @Test
    void theWholeMechanismCommandedPastFullTravelIsDrawnFullyExtendedNoFurther() {
        MechanismSketch atLimit = new MechanismSketch(sar230Slide());
        MechanismSketch overCommanded = new MechanismSketch(sar230Slide());

        atLimit.setTotalTravelInches(TOTAL_TRAVEL_INCHES);
        overCommanded.setTotalTravelInches(TOTAL_TRAVEL_INCHES * 5.0);

        assertEquals(totalDrawnLengthInches(atLimit), totalDrawnLengthInches(overCommanded), 1.0e-12,
                "a 5x over-command must draw exactly the same picture as a full-travel command");
    }

    /** Contract 2: a negative command never draws a stage shorter than retracted. */
    @Test
    void aNegativeCommandIsDrawnRetractedNotInverted() {
        MechanismSketch sketch = new MechanismSketch(sar230Slide());

        sketch.setJointExtensionInches("stage1", -50.0);

        assertEquals(0.0, sketch.getJointExtensionInches("stage1"), 1.0e-12,
                "travel below the low stop must clamp to it");
        assertEquals(COLLAPSED_LENGTH_INCHES, sketch.getDrawnLengthInches("stage1"), 1.0e-12,
                "the base stage must still be drawn at its collapsed length, never negative");
    }

    /** Contract 4: at rest the drawing is the CAD retracted pose — encoder zero is the same place. */
    @Test
    void aFreshSketchIsDrawnAtTheCadRetractedPose() {
        MechanismSketch sketch = new MechanismSketch(sar230Slide());

        assertEquals(COLLAPSED_LENGTH_INCHES, sketch.getDrawnLengthInches("stage1"), ONE_MILLIMETRE_INCHES,
                "collapsed stack should be drawn as the 300 mm SAR230 rail length");
        for (int stage = 2; stage <= STAGE_COUNT; stage++) {
            assertEquals(0.0, sketch.getDrawnLengthInches("stage" + stage), ONE_MILLIMETRE_INCHES,
                    "nested stage " + stage + " contributes no length until it extends");
        }
        assertEquals(0.0, sketch.getTotalTravelInches(), 1.0e-12, "nothing is extended at rest");
    }

    /** R5: the same commands produce the same drawing, every time. */
    @Test
    void redrawingTheSameCommandsProducesAnIdenticalPose() {
        MechanismSketch first = new MechanismSketch(sar230Slide());
        MechanismSketch second = new MechanismSketch(sar230Slide());

        for (double travel = 0.0; travel <= TOTAL_TRAVEL_INCHES; travel += 1.37) {
            first.setTotalTravelInches(travel);
            second.setTotalTravelInches(travel);
            for (int stage = 1; stage <= STAGE_COUNT; stage++) {
                String joint = "stage" + stage;
                assertEquals(first.getDrawnLengthInches(joint), second.getDrawnLengthInches(joint), 0.0,
                        "lengths must match bit-for-bit at travel " + travel);
                assertEquals(first.getDrawnAngleDegrees(joint), second.getDrawnAngleDegrees(joint), 0.0,
                        "angles must match bit-for-bit at travel " + travel);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // IMPLEMENTATION-tier
    // ---------------------------------------------------------------------------------------------

    /** A cascade splits the commanded total across stages in proportion to each stage's own travel. */
    @Test
    void aCascadeSplitsTheCommandedTotalEvenlyAcrossEqualStages() {
        MechanismSketch sketch = new MechanismSketch(sar230Slide());

        sketch.setTotalTravelInches(TOTAL_TRAVEL_INCHES / 2.0);

        for (int stage = 1; stage <= STAGE_COUNT; stage++) {
            assertEquals(STAGE_TRAVEL_INCHES / 2.0, sketch.getJointExtensionInches("stage" + stage), 1.0e-12,
                    "four equal stages at half total travel should each be at half stroke");
        }
        assertEquals(TOTAL_TRAVEL_INCHES / 2.0, sketch.getTotalTravelInches(), 1.0e-9,
                "the per-stage extensions should add back up to what was commanded");
    }

    /** A NaN reading draws a retracted stage rather than aborting the sim. */
    @Test
    void aNonFiniteCommandDrawsTheStageRetracted() {
        MechanismSketch sketch = new MechanismSketch(sar230Slide());
        sketch.setJointExtensionInches("stage3", STAGE_TRAVEL_INCHES);

        sketch.setJointExtensionInches("stage3", Double.NaN);

        assertEquals(0.0, sketch.getJointExtensionInches("stage3"), 1.0e-12,
                "a NaN telemetry read should fall back to retracted, not poison the drawing");
    }

    /** An axis tilted out of the canvas plane is rejected, not silently drawn foreshortened. */
    @Test
    void anAxisOutsideTheCanvasPlaneIsRejected() {
        // Travel points along robot-right (+X), but the canvas plane is the Y-Z side view, so this axis
        // projects to a point. Drawing it would show a stage that never appears to move.
        MechanismSketchDefinition definition = MechanismSketchDefinition.builder("Sideways")
                .canvasSizeInches(24.0, 48.0)
                .canvasAxes(CANVAS_HORIZONTAL, CANVAS_VERTICAL)
                .mountPointInches(RobotVector3.origin())
                .addJoint(new LinearJointDefinition(
                        "stage1", new RobotVector3(1.0, 0.0, 0.0), 1.0, 0.0, 5.0, "#FFFFFF", 4.0))
                .build();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> new MechanismSketch(definition));
        assertTrue(thrown.getMessage().contains("canvas plane"),
                "the error should name the real problem, got: " + thrown.getMessage());
    }

    /** Asking for a stage that does not exist is a caller bug and fails loudly. */
    @Test
    void anUnknownJointNameFailsLoudly() {
        MechanismSketch sketch = new MechanismSketch(sar230Slide());

        assertThrows(IllegalArgumentException.class, () -> sketch.setJointExtensionInches("stage9", 1.0));
    }

    /** Non-perpendicular canvas axes would shear the drawing, so the builder refuses them. */
    @Test
    void nonPerpendicularCanvasAxesAreRejected() {
        MechanismSketchDefinition.Builder builder = MechanismSketchDefinition.builder("Sheared")
                .canvasSizeInches(24.0, 48.0)
                .canvasAxes(CANVAS_VERTICAL, CANVAS_VERTICAL)
                .mountPointInches(RobotVector3.origin())
                .addJoint(new LinearJointDefinition(
                        "stage1", CANVAS_VERTICAL, 1.0, 0.0, 5.0, "#FFFFFF", 4.0));

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    /** A mechanism with no stages draws nothing, which is a definition bug worth catching early. */
    @Test
    void aMechanismWithNoJointsIsRejected() {
        MechanismSketchDefinition.Builder builder = MechanismSketchDefinition.builder("Empty")
                .canvasSizeInches(24.0, 48.0)
                .canvasAxes(CANVAS_HORIZONTAL, CANVAS_VERTICAL)
                .mountPointInches(RobotVector3.origin());

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    /** An un-normalized travel axis would scale every drawn length by an invisible factor. */
    @Test
    void anUnNormalizedAxisIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LinearJointDefinition(
                "stage1", new RobotVector3(0.0, 0.0, 2.0), 1.0, 0.0, 5.0, "#FFFFFF", 4.0));
    }

    /** A stage name containing '/' would fabricate a nesting level and vanish from the tab. */
    @Test
    void aJointNameWithASlashIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LinearJointDefinition(
                "stage/1", CANVAS_VERTICAL, 1.0, 0.0, 5.0, "#FFFFFF", 4.0));
    }

    /** Two stages sharing a name would collide as AdvantageScope log keys and one would vanish silently. */
    @Test
    void aDuplicateJointNameIsRejected() {
        MechanismSketchDefinition.Builder builder = MechanismSketchDefinition.builder("Duplicate")
                .canvasSizeInches(24.0, 48.0)
                .canvasAxes(CANVAS_HORIZONTAL, CANVAS_VERTICAL)
                .mountPointInches(RobotVector3.origin())
                .addJoint(new LinearJointDefinition(
                        "stage1", CANVAS_VERTICAL, 1.0, 0.0, 5.0, "#FFFFFF", 4.0));

        assertThrows(IllegalArgumentException.class, () -> builder.addJoint(new LinearJointDefinition(
                "stage1", CANVAS_VERTICAL, 1.0, 0.0, 5.0, "#FFFFFF", 4.0)));
    }

    // ---------------------------------------------------------------------------------------------
    // LinearJointDefinition validation — each throw branch of its constructor, R9 guardrails
    // ---------------------------------------------------------------------------------------------

    @Test
    void aJointWithANonFiniteAxisComponentIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RobotVector3(Double.NaN, 0.0, 1.0));
    }

    @Test
    void aBlankJointNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LinearJointDefinition(
                "   ", CANVAS_VERTICAL, 1.0, 0.0, 5.0, "#FFFFFF", 4.0));
    }

    @Test
    void aMalformedColorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LinearJointDefinition(
                "stage1", CANVAS_VERTICAL, 1.0, 0.0, 5.0, "blue", 4.0));
    }

    @Test
    void aNegativeRetractedLengthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LinearJointDefinition(
                "stage1", CANVAS_VERTICAL, -1.0, 0.0, 5.0, "#FFFFFF", 4.0));
    }

    @Test
    void minTravelEqualToMaxTravelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LinearJointDefinition(
                "stage1", CANVAS_VERTICAL, 1.0, 5.0, 5.0, "#FFFFFF", 4.0));
    }

    @Test
    void minTravelGreaterThanMaxTravelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LinearJointDefinition(
                "stage1", CANVAS_VERTICAL, 1.0, 5.0, 1.0, "#FFFFFF", 4.0));
    }

    @Test
    void aZeroOrNegativeLineWeightIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LinearJointDefinition(
                "stage1", CANVAS_VERTICAL, 1.0, 0.0, 5.0, "#FFFFFF", 0.0));
    }

    /** {@link RobotVector3#normalized()} has no direction to return for the zero vector. */
    @Test
    void normalizingTheZeroVectorIsRejected() {
        assertThrows(IllegalArgumentException.class, RobotVector3.origin()::normalized);
    }

    private static double totalDrawnLengthInches(MechanismSketch sketch) {
        double total = 0.0;
        for (LinearJointDefinition joint : sketch.getDefinition().getLinearJoints()) {
            total += sketch.getDrawnLengthInches(joint.getName());
        }
        return total;
    }
}
