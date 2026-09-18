package org.horizon36596.simloop.viz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.psilynx.psikit.core.Logger;
import org.psilynx.psikit.core.mechanism.LoggedMechanism2d;
import org.psilynx.psikit.core.mechanism.LoggedMechanismLigament2d;
import org.psilynx.psikit.core.mechanism.LoggedMechanismRoot2d;
import org.psilynx.psikit.core.wpi.Color8Bit;

/**
 * Draws a {@link MechanismSketchDefinition} into AdvantageScope's <b>Mechanism</b> tab: it owns a PsiKit
 * {@link LoggedMechanism2d}, keeps one ligament per joint, and updates them as the mechanism moves.
 *
 * <p><b>It wraps PsiKit; it does not reimplement it.</b> {@code org.psilynx.psikit.core.mechanism} already
 * emits exactly the key schema AdvantageScope reads — verified against both the WPILib source and the
 * shipped jar's own string constants ({@code docs/reference/ascope-mechanism-format.md}). Hand-writing
 * {@code .type} / {@code angle} / {@code length} keys here would duplicate a tested library and drift the
 * moment PsiKit changes.
 *
 * <p><b>What this class actually adds</b> over calling PsiKit directly is the part the renderer refuses to
 * do: it <b>clamps every joint to its CAD-declared limits</b>. {@code Mechanism2d} will happily draw a
 * slide extended past its own hard stop or an arm swung through its own frame, which is a picture of
 * something that cannot happen. Clamping here is B12 Contract line 2, extended to angles by B13v Contract
 * clause 2, and it is the reason the season layer should never touch the ligaments directly.
 *
 * <h2>Stacked degrees of freedom</h2>
 *
 * <p>A chain may mix the two joint kinds freely — an arm pivot with a slide riding on it is a
 * {@link RevoluteJointDefinition} followed by a {@link LinearJointDefinition}. Each kind moves exactly one
 * of a ligament's two numbers: a linear joint moves its <b>length</b>, a revolute joint moves its
 * <b>angle</b> (see {@link JointDefinition}).
 *
 * <p><b>Why that is enough, with no kinematics here.</b> {@code Mechanism2d} angles are <b>relative to the
 * parent ligament</b>, so when a pivot swings, everything drawn beyond it swings too — the renderer does
 * that, not this class. So the chain's angles are computed <b>once, at the zero pose</b>, and a revolute
 * joint's live motion is simply added to its own relative angle. A slide riding on a pivot needs no
 * recomputation as the pivot moves: it keeps pointing along its parent by construction. Nothing here
 * re-implements forward kinematics, which is the same reason the drivetrain is not re-implemented for the
 * viewer (domain R1).
 *
 * <p><b>Projection, and the one thing this cannot draw.</b> The tab is 2-D, so the robot-local mechanism is
 * projected onto the plane spanned by the definition's two canvas axes. Two rejections follow, both at
 * construction rather than drawn wrong:
 * <ul>
 *   <li>A joint whose zero-pose direction is tilted <b>out of</b> the canvas plane would be drawn
 *       foreshortened, so its on-screen length would silently disagree with CAD (B12 Contract line 1).</li>
 *   <li>A revolute joint whose rotation axis does not stand perpendicular to the canvas plane — a
 *       <b>turret yaw</b> — cannot be drawn at all. A {@code Mechanism2d} carries two numbers per segment
 *       and its plane is a fixed viewer setting, so there is nowhere for a yaw to live; see
 *       {@code docs/briefs/b13-stacked-dof-viz.md} §1 for the proof and §1.2 for the asset-free
 *       alternatives (B13v Contract clause 5).</li>
 * </ul>
 *
 * <p><b>Angles.</b> {@code Mechanism2d} ligament angles are <b>degrees, relative to the parent ligament</b>.
 * This class computes each joint's absolute in-plane angle at the zero pose, then stores the difference
 * from the joint below, so callers only ever deal in <b>radians</b> measured from the CAD pose (B13v
 * Contract clause 6).
 *
 * <p><b>Determinism (domain R5).</b> No wall-clock read; the drawn state is a pure function of the values
 * passed to the setters. Trigonometry goes through {@link StrictMath} so replays are bit-stable across
 * JVMs, matching {@code Mechanism1DofPlant}.
 *
 * <p><b>Season-agnostic (domain R7).</b> Depends only on PsiKit and this package; no {@code teamcode} import.
 *
 * <p><b>Two tabs, one log key.</b> The same recorded mechanism serves AdvantageScope's 2-D <b>Mechanism</b>
 * tab (drop the key straight in) and its <b>3D Field</b> tab (attach the key to a robot pose object, and it
 * is drawn as boxes riding along with the robot). The 3-D case drives two things this class does that
 * calling PsiKit directly would not:
 * <ul>
 *   <li><b>It writes metres</b>, because the 3-D field shares the field's coordinate space — see
 *       {@link #METRES_PER_INCH}.</li>
 *   <li><b>It shifts the root half a canvas width</b>, because AdvantageScope puts the robot's origin at
 *       the centre of the canvas's <em>bottom edge</em>, not at canvas {@code (0, 0)} — see
 *       {@link #toCanvasXInches}. Without the shift the mechanism renders floating beside the robot.</li>
 * </ul>
 *
 * <p>The definition's canvas axes matter too: they decide which of the robot's planes the mechanism lies in,
 * and must match the XZ/YZ choice made in the tab.
 */
public final class MechanismSketch {

    /**
     * How close a direction has to be to lying in (or standing perpendicular to) the canvas plane before it
     * is rejected.
     *
     * <p><b>Why 1e-5 and not the 1e-6 the inputs are checked at.</b> {@link RobotVector3#isUnitLength()}
     * admits a length of {@code 1 ± 1e-6} and {@link RobotVector3#isPerpendicularTo} admits a dot product
     * of {@code 1e-6}, and those slops <em>multiply</em> here: a direction dotted against two axes that are
     * each a whole tolerance long can land about {@code 2e-6} off, so a gate at {@code 1e-6} would reject
     * input this API documents as legal — an arm pivot would be reported as an undrawable turret yaw. The
     * gate therefore sits an order of magnitude above the stack-up. It is still far tighter than any real
     * modelling error: {@code 1e-5} on a unit direction is about {@code 0.0006} degrees.
     */
    private static final double IN_PLANE_TOLERANCE = 1.0e-5;

    /**
     * Inches → metres, applied <b>only</b> where this class writes into PsiKit.
     *
     * <p><b>Why the drawing is metres while this whole class speaks inches.</b> AdvantageScope's <b>3D
     * Field</b> tab can attach a {@code Mechanism2d} to a robot pose and draw it as boxes riding along with
     * the robot. That drawing shares the field's own coordinate space, which is metres — the same metres
     * {@code Drive/pose2d} is already logged in. A mechanism handed over in inches renders about 39× too
     * big: a 0.72 m slide becomes a 28 m tower next to a 3.66 m field. The 2-D <b>Mechanism</b> tab, by
     * contrast, is unit-agnostic (its canvas is unitless and simply scales), so metres is correct for that
     * tab too and one log key feeds both.
     *
     * <p>Everything a human touches stays in inches: every parameter, every getter, and every
     * {@code recordOutput} the season layer writes. The conversion happens here, at the boundary, and
     * nowhere else — so there is exactly one place to look when a number seems 39× off.
     */
    private static final double METRES_PER_INCH = 0.0254;


    private final MechanismSketchDefinition definition;
    private final LoggedMechanism2d mechanism;
    private final LoggedMechanismRoot2d root;

    /** One drawn segment per joint, base first — index-aligned with {@code definition.getJoints()}. */
    private final List<DrawnJoint> drawnJoints;

    /** Current clamped extension per <b>linear</b> joint (in), keyed by joint name, in chain order. */
    private final Map<String, Double> extensionInchesByLinearJoint = new LinkedHashMap<String, Double>();

    /** Current clamped angle per <b>revolute</b> joint (rad), keyed by joint name, in chain order. */
    private final Map<String, Double> angleRadiansByRevoluteJoint = new LinkedHashMap<String, Double>();

    /**
     * How far the mount sits from the robot's origin along the canvas horizontal axis (in), <b>signed</b>:
     * negative is behind the robot's centre when the horizontal axis is robot-forward. This is the physical
     * offset out of CAD, before {@link #toCanvasXInches} shifts it onto the canvas.
     */
    private final double mountAcrossRobotInches;

    /**
     * The root's position in <b>canvas</b> coordinates (in) — what AdvantageScope actually reads. Differs
     * from {@link #mountAcrossRobotInches} by half the canvas width; see {@link #toCanvasXInches}.
     */
    private final double rootCanvasXInches;
    private final double rootCanvasYInches;

    /**
     * Builds the drawing at the mechanism's zero pose — every linear stage retracted, every pivot at the
     * angle CAD drew it at.
     *
     * @param definition what to draw; see {@link MechanismSketchDefinition}.
     * @throws IllegalArgumentException if {@code definition} is null, if any joint's zero-pose direction
     *                                  does not lie in the canvas plane (which would draw it
     *                                  foreshortened), or if a revolute joint's rotation axis does not
     *                                  stand perpendicular to that plane (a yaw, which cannot be drawn at
     *                                  all).
     */
    public MechanismSketch(MechanismSketchDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("MechanismSketch needs a definition to draw");
        }
        this.definition = definition;
        this.mechanism = new LoggedMechanism2d(
                toMetres(definition.getCanvasWidthInches()),
                toMetres(definition.getCanvasHeightInches()),
                new Color8Bit(definition.getBackgroundColorHex()));

        RobotVector3 horizontal = definition.getCanvasHorizontalAxis();
        RobotVector3 vertical = definition.getCanvasVerticalAxis();
        RobotVector3 mount = definition.getMountPointInches();

        // The canvas plane's normal: the one axis a pivot drawn on this canvas may turn about. The two
        // canvas axes are unit and perpendicular, so the cross product is already unit to within their own
        // tolerances — normalizing anyway keeps one source of slop out of the comparison below, which is a
        // gate a legitimate pivot must not trip.
        RobotVector3 canvasNormal = horizontal.cross(vertical).normalized();

        this.mountAcrossRobotInches = mount.dot(horizontal);
        this.rootCanvasXInches =
                toCanvasXInches(mountAcrossRobotInches, definition.getCanvasWidthInches());
        this.rootCanvasYInches = mount.dot(vertical);
        this.root = mechanism.getRoot(
                definition.getName() + "Root", toMetres(rootCanvasXInches), toMetres(rootCanvasYInches));

        this.drawnJoints = new ArrayList<DrawnJoint>();
        double parentAbsoluteAngleDegrees = 0.0;
        for (JointDefinition joint : definition.getJoints()) {
            RobotVector3 zeroPoseDirection = joint.getZeroPoseDirection();
            double acrossCanvas = zeroPoseDirection.dot(horizontal);
            double upCanvas = zeroPoseDirection.dot(vertical);

            // The direction is a unit vector, so its in-plane projection also has length 1 exactly when the
            // direction lies in the canvas plane. Anything less means part of the segment points out of the
            // picture and its drawn length would be shorter than its real length.
            double inPlaneLength = Math.sqrt(acrossCanvas * acrossCanvas + upCanvas * upCanvas);
            if (Math.abs(inPlaneLength - 1.0) > IN_PLANE_TOLERANCE) {
                throw new IllegalArgumentException(
                        "joint \"" + joint.getName() + "\" zero-pose direction " + zeroPoseDirection
                                + " does not lie in the canvas plane (in-plane length " + inPlaneLength
                                + "); it would be drawn foreshortened, so its on-screen length would"
                                + " disagree with CAD. Choose canvas axes that contain the mechanism.");
            }

            // Absolute angle of this segment on the canvas at the ZERO POSE. Stored relative to the segment
            // below it, because that is the convention AdvantageScope reads — and because it is what makes
            // a pivot carry everything beyond it (see this class's "Stacked degrees of freedom").
            double absoluteAngleDegrees = toDegrees(StrictMath.atan2(upCanvas, acrossCanvas));
            double relativeZeroAngleDegrees = absoluteAngleDegrees - parentAbsoluteAngleDegrees;

            double canvasRotationSign = 0.0;
            if (joint.getKind() == JointDefinition.Kind.REVOLUTE) {
                canvasRotationSign =
                        canvasRotationSignOf((RevoluteJointDefinition) joint, canvasNormal);
            }

            LoggedMechanismLigament2d ligament = new LoggedMechanismLigament2d(
                    joint.getName(),
                    toMetres(joint.getZeroPoseLengthInches()),
                    relativeZeroAngleDegrees,
                    joint.getLineWeightPixels(),
                    new Color8Bit(joint.getColorHex()));

            // Joint i+1 hangs off the tip of joint i, so the chain telescopes and swings as one.
            if (drawnJoints.isEmpty()) {
                root.append(ligament);
            } else {
                drawnJoints.get(drawnJoints.size() - 1).ligament.append(ligament);
            }
            drawnJoints.add(new DrawnJoint(joint, ligament, relativeZeroAngleDegrees, canvasRotationSign));

            if (joint.getKind() == JointDefinition.Kind.LINEAR) {
                LinearJointDefinition stage = (LinearJointDefinition) joint;
                extensionInchesByLinearJoint.put(
                        stage.getName(), stage.clampTravel(stage.getMinTravelInches()));
            } else {
                // Zero is always reachable — RevoluteJointDefinition rejects a range that excludes it — so
                // the drawing starts at exactly the pose CAD drew (Contract clause 3).
                angleRadiansByRevoluteJoint.put(joint.getName(), 0.0);
            }
            parentAbsoluteAngleDegrees = absoluteAngleDegrees;
        }
    }

    /**
     * Set one <b>linear</b> stage's extension along its own travel axis.
     *
     * <p>The value is <b>clamped</b> to that stage's {@code [minTravelInches, maxTravelInches]}. Commanding
     * past a hard stop draws the stage at the stop, never beyond it (B12 Contract line 2). A non-finite
     * value draws the stage retracted rather than throwing — a bad telemetry read should not abort a sim.
     *
     * @param jointName       the stage's name, as given to {@link LinearJointDefinition}.
     * @param extensionInches extension from retracted (in).
     * @throws IllegalArgumentException if this mechanism has no joint by that name, or if that joint is a
     *                                  pivot rather than a stage.
     */
    public void setJointExtensionInches(String jointName, double extensionInches) {
        DrawnJoint drawn = linearJoint(jointName, "setJointExtensionInches");
        LinearJointDefinition stage = (LinearJointDefinition) drawn.joint;
        double clamped = stage.clampTravel(extensionInches);
        extensionInchesByLinearJoint.put(jointName, clamped);
        drawn.ligament.setLength(toMetres(stage.getRetractedLengthInches() + clamped));
    }

    /**
     * Set one <b>revolute</b> joint's angle, measured in radians from the pose the mechanism is drawn in in
     * CAD — so {@code 0} always draws the CAD pose (B13v Contract clause 3), and the sign follows the
     * right-hand rule about the joint's rotation axis.
     *
     * <p>The value is <b>clamped</b> to that joint's {@code [minAngleRadians, maxAngleRadians]}: an arm
     * commanded through its own hard stop draws at the stop (Contract clause 2). A non-finite value draws
     * the joint at zero rather than throwing.
     *
     * <p>Everything drawn beyond this joint swings with it, because AdvantageScope's angles are relative to
     * the parent. Nothing else needs updating.
     *
     * @param jointName    the pivot's name, as given to {@link RevoluteJointDefinition}.
     * @param angleRadians angle from the CAD pose (rad).
     * @throws IllegalArgumentException if this mechanism has no joint by that name, or if that joint is a
     *                                  stage rather than a pivot.
     */
    public void setJointAngleRadians(String jointName, double angleRadians) {
        DrawnJoint drawn = revoluteJoint(jointName, "setJointAngleRadians");
        RevoluteJointDefinition pivot = (RevoluteJointDefinition) drawn.joint;
        double clamped = pivot.clampAngle(angleRadians);
        angleRadiansByRevoluteJoint.put(jointName, clamped);
        drawn.ligament.setAngle(
                drawn.relativeZeroAngleDegrees + toDegrees(drawn.canvasRotationSign * clamped));
    }

    /**
     * Set the whole mechanism's extension, splitting it across the <b>linear</b> stages in proportion to
     * how much travel each has. This is the cascading-slide case: several stages rigged to move together
     * off one encoder, where the robot code knows the total but not the per-stage split. Pivots in the same
     * chain are left exactly where they are.
     *
     * <p>The total is clamped to {@code [0, }{@link MechanismSketchDefinition#getMaxTotalTravelInches()}
     * {@code ]} first, so an over-command extends every stage to its own stop and no further. Each stage's
     * share is {@code minTravelInches + (maxTravelInches - minTravelInches) * fractionExtended} — general
     * enough to stay correct even if a future stage has a non-zero {@code minTravelInches} (every stage in
     * this repo today has {@code min = 0}, so the extra term is currently always zero).
     *
     * @param totalExtensionInches total extension of the whole mechanism (in).
     * @throws IllegalStateException if the mechanism has no linear stages at all — a pure pivot chain has
     *                               no travel to split, and silently doing nothing would hide the mistake.
     */
    public void setTotalTravelInches(double totalExtensionInches) {
        List<LinearJointDefinition> stages = definition.getLinearJoints();
        if (stages.isEmpty()) {
            throw new IllegalStateException(
                    "mechanism \"" + definition.getName() + "\" has no linear stages, so there is no travel"
                            + " to split — setTotalTravelInches is the cascading-slide helper. Use"
                            + " setJointAngleRadians for a pivot.");
        }
        double totalTravel = definition.getMaxTotalTravelInches();
        if (totalTravel <= 0.0) {
            // Every stage is required to have min < max, but nothing stops max from being zero or negative,
            // so a chain of such stages sums to no travel at all. Dividing by it would give NaN, which
            // clamps back to each stage's minimum — the mechanism would sit still and look correct while
            // ignoring every command.
            throw new IllegalStateException(
                    "mechanism \"" + definition.getName() + "\" has linear stages but no positive travel"
                            + " (their maxTravelInches sum to " + totalTravel + "), so there is nothing to"
                            + " split. Check the stage travel limits.");
        }
        double clampedTotal = Double.isFinite(totalExtensionInches)
                ? Math.max(0.0, Math.min(totalTravel, totalExtensionInches))
                : 0.0;
        double fractionExtended = clampedTotal / totalTravel;
        for (LinearJointDefinition stage : stages) {
            double stageRange = stage.getMaxTravelInches() - stage.getMinTravelInches();
            setJointExtensionInches(
                    stage.getName(), stage.getMinTravelInches() + stageRange * fractionExtended);
        }
    }

    /**
     * Publish the current pose to AdvantageScope under {@code key}.
     *
     * <p>Call once per loop, after the joint positions have been set — {@code Mechanism2d} logs a snapshot,
     * not a live object, so a cycle without this call leaves the tab showing the previous pose.
     *
     * @param key the AdvantageScope log key, e.g. {@code "Slide/Mechanism"}.
     */
    public void record(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("mechanism log key must be a non-blank string");
        }
        Logger.recordOutput(key, mechanism);
    }

    /**
     * A linear stage's current clamped extension.
     *
     * @param jointName the joint's name, as given to its definition
     * @return the extension in inches, clamped to the stage's travel
     */
    public double getJointExtensionInches(String jointName) {
        linearJoint(jointName, "getJointExtensionInches");
        return extensionInchesByLinearJoint.get(jointName);
    }

    /**
     * A revolute joint's current clamped angle (rad), measured from the pose the mechanism is drawn in in
     * CAD.
     *
     * @param jointName the joint's name, as given to its definition
     * @return the angle in radians, clamped to the joint's limits, measured from the CAD pose
     */
    public double getJointAngleRadians(String jointName) {
        revoluteJoint(jointName, "getJointAngleRadians");
        return angleRadiansByRevoluteJoint.get(jointName);
    }

    /**
     * Sum of every <b>linear</b> stage's current extension (in) — what the mechanism is extended by. Pivots
     * contribute nothing; their state is in radians and has no place in this sum.
     *
     * <p>A chain of pure pivots therefore reads {@code 0.0} here, meaning "this mechanism has no stages",
     * not "its stages are retracted". {@link #setTotalTravelInches(double)} throws on that same chain
     * rather than returning quietly, because a caller writing travel to a mechanism that has none is
     * making a mistake, while a caller reading it is not.
     *
     * @return the summed extension of every linear stage, in inches; {@code 0.0} for an all-pivot chain
     */
    public double getTotalTravelInches() {
        double total = 0.0;
        for (Double extension : extensionInchesByLinearJoint.values()) {
            total += extension;
        }
        return total;
    }

    /**
     * The joint's currently drawn length (in): a stage's retracted length plus its clamped extension, or a
     * pivot's constant arm length.
     *
     * <p>Reads the ligament back and converts out of the metres it is stored in (see
     * {@link #METRES_PER_INCH}) — so this returns what was put in, in the units the rest of this class
     * speaks.
     *
     * @param jointName the joint's name, as given to its definition
     * @return the drawn length in inches
     */
    public double getDrawnLengthInches(String jointName) {
        return toInches(drawnJoints.get(indexOfJoint(jointName)).ligament.getLength());
    }

    /**
     * The same joint length as {@link #getDrawnLengthInches(String)}, read in the <b>metres</b> it is stored
     * in — so the value {@link #record(String)} will hand to AdvantageScope, before this class converts it
     * back for callers.
     *
     * <p>Exists so the inches → metres boundary (see {@link #METRES_PER_INCH}) can be checked from outside
     * this class. If a mechanism looks about 39× too big or too small in the 3D Field tab, this is the value
     * to print. Nothing that computes with mechanism geometry should use it — every other method here, and
     * every telemetry value the season layer writes, is inches.
     *
     * @param jointName the joint's name, as given to its definition
     * @return the drawn length in metres, exactly as it is stored and logged
     */
    public double getDrawnLengthMetres(String jointName) {
        return drawnJoints.get(indexOfJoint(jointName)).ligament.getLength();
    }

    /**
     * The joint's drawn angle, <b>relative to the joint below it</b> (AdvantageScope's convention).
     *
     * @param jointName the joint's name, as given to its definition
     * @return the drawn angle in degrees, relative to the joint below
     */
    public double getDrawnAngleDegrees(String jointName) {
        return drawnJoints.get(indexOfJoint(jointName)).ligament.getAngle();
    }

    /** {@return what this sketch was built from} */
    public MechanismSketchDefinition getDefinition() {
        return definition;
    }

    /**
     * The root's <b>canvas</b> x (in) — the number written to the log, already shifted by half the canvas
     * width so AdvantageScope lands it on the robot. See {@link #toCanvasXInches}. For where the mechanism
     * actually sits on the robot, use {@link #getMountAcrossRobotInches()}.
     *
     * @return the root's canvas X in inches, already shifted by half the canvas width
     */
    public double getRootCanvasXInches() {
        return rootCanvasXInches;
    }

    /**
     * The root's <b>canvas</b> y (in) — the number written to the log. No shift is applied vertically: the
     * canvas's bottom edge already is the robot's origin height.
     *
     * @return the root's canvas Y in inches, measured up from the canvas's bottom edge
     */
    public double getRootCanvasYInches() {
        return rootCanvasYInches;
    }

    /**
     * Where the mechanism mounts on the robot, measured along the canvas horizontal axis (in) and
     * <b>signed</b> — negative is behind the robot's centre when that axis is robot-forward. This is the
     * physical fact out of CAD; {@link #getRootCanvasXInches()} is the same point in canvas coordinates.
     *
     * @return the signed mount offset along the canvas horizontal axis, in inches, in the robot frame
     */
    public double getMountAcrossRobotInches() {
        return mountAcrossRobotInches;
    }

    /**
     * One joint as it is actually drawn: the ligament, plus the two numbers that turn a joint's live state
     * into what the ligament is set to. Kept together in one place so a reader is not chasing parallel
     * lists.
     */
    private static final class DrawnJoint {

        /** The joint this was built from. */
        private final JointDefinition joint;

        /** The PsiKit ligament AdvantageScope actually reads. */
        private final LoggedMechanismLigament2d ligament;

        /**
         * The joint's drawn angle at the zero pose (deg), relative to the joint below it. A revolute
         * joint's live angle is added to this; a linear joint never moves off it.
         */
        private final double relativeZeroAngleDegrees;

        /**
         * {@code +1} or {@code -1} for a revolute joint, {@code 0} for a linear one: whether a positive
         * angle about the joint's rotation axis turns the segment the same way canvas angles increase.
         * Right-handed rotation about the canvas normal sweeps the horizontal axis toward the vertical one,
         * which is also the direction {@code atan2} counts, so an axis along the normal gives {@code +1}
         * and an axis against it gives {@code -1}.
         */
        private final double canvasRotationSign;

        private DrawnJoint(
                JointDefinition joint,
                LoggedMechanismLigament2d ligament,
                double relativeZeroAngleDegrees,
                double canvasRotationSign) {
            this.joint = joint;
            this.ligament = ligament;
            this.relativeZeroAngleDegrees = relativeZeroAngleDegrees;
            this.canvasRotationSign = canvasRotationSign;
        }
    }

    /**
     * Which way a pivot's positive angle turns on the canvas, and the place a <b>turret yaw</b> is caught.
     *
     * <p>Both vectors are unit, so their dot product is {@code +1} when the rotation axis points along the
     * canvas normal, {@code -1} when it points against it, and anything in between when the joint sweeps
     * out of the canvas plane — which no {@code Mechanism2d} can show, at any plane setting, because the
     * plane is a static viewer setting and a yaw is time-varying data.
     */
    private static double canvasRotationSignOf(RevoluteJointDefinition pivot, RobotVector3 canvasNormal) {
        double alignment = pivot.getRotationAxis().dot(canvasNormal);
        if (Math.abs(Math.abs(alignment) - 1.0) > IN_PLANE_TOLERANCE) {
            throw new IllegalArgumentException(
                    "joint \"" + pivot.getName() + "\" turns about " + pivot.getRotationAxis()
                            + ", which is not perpendicular to the canvas plane (normal " + canvasNormal
                            + "), so it sweeps out of the picture. AdvantageScope's Mechanism2d cannot draw"
                            + " a yaw DOF at all — its plane is a fixed viewer setting and a segment"
                            + " carries only a length and an angle. See"
                            + " docs/briefs/b13-stacked-dof-viz.md §1.2 for the alternatives (a Cone or"
                            + " Axes object, or a ghost object carrying the yaw).");
        }
        return alignment > 0.0 ? 1.0 : -1.0;
    }

    /** The named joint, or a message saying it is the wrong kind for {@code method}. */
    private DrawnJoint linearJoint(String jointName, String method) {
        DrawnJoint drawn = drawnJoints.get(indexOfJoint(jointName));
        if (drawn.joint.getKind() != JointDefinition.Kind.LINEAR) {
            throw new IllegalArgumentException(
                    "joint \"" + jointName + "\" is a pivot, not a stage, so " + method + " does not apply"
                            + " to it — a pivot's state is an angle. Use the *AngleRadians methods.");
        }
        return drawn;
    }

    /** The named joint, or a message saying it is the wrong kind for {@code method}. */
    private DrawnJoint revoluteJoint(String jointName, String method) {
        DrawnJoint drawn = drawnJoints.get(indexOfJoint(jointName));
        if (drawn.joint.getKind() != JointDefinition.Kind.REVOLUTE) {
            throw new IllegalArgumentException(
                    "joint \"" + jointName + "\" is a stage, not a pivot, so " + method + " does not apply"
                            + " to it — a stage's state is an extension. Use the *ExtensionInches methods.");
        }
        return drawn;
    }

    private int indexOfJoint(String jointName) {
        List<JointDefinition> joints = definition.getJoints();
        for (int i = 0; i < joints.size(); i++) {
            if (joints.get(i).getName().equals(jointName)) {
                return i;
            }
        }
        List<String> names = new ArrayList<String>();
        for (JointDefinition joint : joints) {
            names.add(joint.getName());
        }
        throw new IllegalArgumentException(
                "mechanism \"" + definition.getName() + "\" has no joint named \"" + jointName
                        + "\"; it has " + names);
    }

    // Math.toDegrees is a plain multiply, but spelling it out keeps every floating-point operation in this
    // class explicitly StrictMath-or-exact, so a reader does not have to check which ones are strict (R5).
    private static double toDegrees(double radians) {
        return radians * (180.0 / Math.PI);
    }

    /**
     * Turns a signed robot-relative offset into the canvas x AdvantageScope expects, by shifting it half a
     * canvas width to the right.
     *
     * <p><b>Why any shift at all.</b> The 3D Field tab's docs say it plainly: <i>"The robot's origin is
     * centered on the bottom edge of the mechanism."</i> The canvas is therefore <b>not</b> a robot-relative
     * coordinate space — a canvas point {@code (x, y)} is drawn on the robot at
     * {@code (x - canvasWidth/2, y)}. Write the raw robot-relative offset and the whole mechanism renders
     * half a canvas width away from the robot, floating beside it: on a 24 in canvas, 12 in off. That is
     * exactly what happened before this shift existed.
     *
     * <p>Three consequences worth knowing:
     * <ul>
     *   <li>The mount's robot-relative offset stays signed and physical — a rear mount is negative — and
     *       that is what the season layer's numbers and tests talk about. Only the canvas coordinate moves.</li>
     *   <li>Canvas coordinates come out positive for any mechanism that fits, so the same log key also draws
     *       correctly in the 2-D <b>Mechanism</b> tab, which renders {@code [0, width] x [0, height]}.</li>
     *   <li>The canvas has to be at least twice the mechanism's furthest horizontal reach, or the shift
     *       pushes it off the opposite edge. {@code MechanismSketchDefinition} does not enforce that,
     *       because it cannot know a joint's extended reach; the season layer's tests do. With a pivot in
     *       the chain that reach must be checked across the whole swept range, not at one pose.</li>
     * </ul>
     *
     * <p>Vertical needs no equivalent, because the canvas's bottom edge already sits at the robot's origin
     * height — this repo's mechanism frame has its origin at floor level, which is the same place.
     */
    private static double toCanvasXInches(double acrossRobotInches, double canvasWidthInches) {
        return canvasWidthInches / 2.0 + acrossRobotInches;
    }

    /** Inches in, metres out — the one place this class crosses into AdvantageScope's units. */
    private static double toMetres(double inches) {
        return inches * METRES_PER_INCH;
    }

    /** Metres in, inches out — reading a drawn value back into this class's own units. */
    private static double toInches(double metres) {
        return metres / METRES_PER_INCH;
    }
}
