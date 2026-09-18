package org.horizon36596.simloop.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * INVARIANT-tier (domain R9) — the geometry half of Contract clause 2: a trigger volume is attached to
 * the robot, so which pieces it overlaps must follow the robot's position <b>and</b> its heading, in the
 * frame the rest of the repo uses (FTC-Cartesian, +X right, +Y forward, heading CCW-positive with the
 * robot's forward direction at {@code (cos, sin)} — conventions §7).
 *
 * <p>These are directional cases on purpose rather than a rotation-invariance property: a sign error in
 * the field-to-robot rotation is still perfectly rotation-invariant, so only naming which way is forward
 * and which way is left can catch one. A reimplementation that got the frame right would pass all of
 * these; one that mixed up forward and left, or flipped a sign, could not.
 */
class TriggerVolumeTest {

    /** A mouth across the front of the robot: 6 in deep, 16 in wide, centred 8 in ahead of robot centre. */
    private static TriggerVolume frontMouth() {
        return new TriggerVolume("intake", 8.0, 0.0, 6.0, 16.0);
    }

    private static final double FACING_PLUS_Y = Math.PI / 2.0;   // robot forward = field +Y
    private static final double FACING_PLUS_X = 0.0;             // robot forward = field +X

    @Test
    void mouthOverlapsAPieceInFrontOfTheRobotAndNotOneBehindOrBeside() {
        TriggerVolume mouth = frontMouth();

        // Robot at field centre, facing +Y. Its mouth spans forward 5..11 in, i.e. field y in [5, 11].
        assertTrue(mouth.overlapsPiece(0.0, 0.0, FACING_PLUS_Y, 0.0, 8.0, 0.0),
                "a point piece 8 in straight ahead sits in the middle of the mouth");
        assertFalse(mouth.overlapsPiece(0.0, 0.0, FACING_PLUS_Y, 0.0, -8.0, 0.0),
                "a piece 8 in BEHIND the robot must not be in a front-mounted mouth");
        assertFalse(mouth.overlapsPiece(0.0, 0.0, FACING_PLUS_Y, 8.0, 0.0, 0.0),
                "a piece 8 in to the robot's RIGHT must not be in a front-mounted mouth");
    }

    @Test
    void theMouthTurnsWithTheRobotsHeading() {
        TriggerVolume mouth = frontMouth();

        // Same robot position, rotated to face +X: the piece that was ahead is now beside it, and vice
        // versa. This is the whole point of attaching the volume to the robot rather than the field.
        assertTrue(mouth.overlapsPiece(0.0, 0.0, FACING_PLUS_X, 8.0, 0.0, 0.0),
                "facing +X, the piece 8 in along +X is the one straight ahead");
        assertFalse(mouth.overlapsPiece(0.0, 0.0, FACING_PLUS_X, 0.0, 8.0, 0.0),
                "facing +X, the piece 8 in along +Y is off to the side");
    }

    @Test
    void theMouthTranslatesWithTheRobotsPosition() {
        TriggerVolume mouth = frontMouth();

        // Drive the robot 30 in along +X and the same relative geometry must still hold.
        assertTrue(mouth.overlapsPiece(30.0, 0.0, FACING_PLUS_Y, 30.0, 8.0, 0.0),
                "the mouth moves with the robot, so 8 in ahead of its new position is still inside it");
        assertFalse(mouth.overlapsPiece(30.0, 0.0, FACING_PLUS_Y, 0.0, 8.0, 0.0),
                "the spot 8 in ahead of where the robot USED to be is no longer inside the mouth");
    }

    @Test
    void aLeftOffsetMouthSitsOnTheRobotsLeftNotItsRight() {
        // A mouth centred 8 in to the robot's left, at robot centre fore-aft.
        TriggerVolume leftMouth = new TriggerVolume("leftIntake", 0.0, 8.0, 6.0, 6.0);

        // Facing +Y, the robot's left is field -X (left = (-sin, cos) = (-1, 0) at heading pi/2).
        assertTrue(leftMouth.overlapsPiece(0.0, 0.0, FACING_PLUS_Y, -8.0, 0.0, 0.0),
                "facing +Y, the robot's left is field -X");
        assertFalse(leftMouth.overlapsPiece(0.0, 0.0, FACING_PLUS_Y, 8.0, 0.0, 0.0),
                "field +X is the robot's RIGHT when it faces +Y, so a left-mounted mouth misses it");
    }

    @Test
    void aPieceCountsWhenItsBodyReachesTheMouthNotOnlyItsCentre() {
        TriggerVolume mouth = frontMouth();

        // Facing +X so cos/sin are exactly 1 and 0 and the arithmetic below is exact — the edge cases in
        // this test and the next one sit ON the boundary, and at heading pi/2 cos() is 6e-17 rather than
        // zero, which would decide a "touching exactly" case by rounding noise instead of by the rule.
        // The direction-sign tests above cover heading pi/2, where every margin is inches wide.
        //
        // Facing +X the mouth spans field x in [5, 11], y in [-8, 8].
        assertTrue(mouth.overlapsPiece(0.0, 0.0, FACING_PLUS_X, 4.0, 0.0, 1.4),
                "a ball whose body reaches into the mouth is acquirable even though its centre is outside");
        assertFalse(mouth.overlapsPiece(0.0, 0.0, FACING_PLUS_X, 4.0, 0.0, 0.0),
                "the same spot with a point-sized piece is outside the mouth, so the radius is what did it");

        // Touching exactly counts (documented behaviour): a radius-1.5 piece centred at x = 3.5 reaches
        // the near edge at x = 5 and no further.
        assertTrue(mouth.overlapsPiece(0.0, 0.0, FACING_PLUS_X, 3.5, 0.0, 1.5),
                "a ball exactly touching the mouth edge counts as overlapping");
        assertFalse(mouth.overlapsPiece(0.0, 0.0, FACING_PLUS_X, 3.5, 0.0, 1.25),
                "a shade smaller and it falls short of the same edge");
    }

    @Test
    void aPieceDiagonallyOffTheCornerIsJudgedByItsDistanceToTheCorner() {
        TriggerVolume mouth = frontMouth();

        // Facing +X (exact trig, see above) the near-right corner of the mouth is field (5, -8). A piece
        // 3-4-5 away from that corner overlaps only if its radius reaches the full 5 — which is the check
        // that the two axes are combined as a distance rather than tested independently (a mouth that
        // tested each axis on its own would call the radius-1 case below a hit).
        assertTrue(mouth.overlapsPiece(0.0, 0.0, FACING_PLUS_X, 2.0, -12.0, 5.0),
                "radius 5 reaches the corner from 5 in away (3-4-5), so it overlaps");
        assertFalse(mouth.overlapsPiece(0.0, 0.0, FACING_PLUS_X, 2.0, -12.0, 4.0),
                "radius 4 falls short of the same corner even though it clears the 3 in fore-aft gap");
    }

    @Test
    void geometryIsRejectedRatherThanSilentlyAcceptedWhenItCannotBeMeaningful() {
        assertThrows(IllegalArgumentException.class,
                () -> new TriggerVolume(null, 0.0, 0.0, 6.0, 6.0), "a volume must be named for the log");
        assertThrows(IllegalArgumentException.class,
                () -> new TriggerVolume("  ", 0.0, 0.0, 6.0, 6.0), "a blank name is not a name");
        assertThrows(IllegalArgumentException.class,
                () -> new TriggerVolume("m", 0.0, 0.0, 0.0, 6.0), "a zero-depth mouth can never acquire");
        assertThrows(IllegalArgumentException.class,
                () -> new TriggerVolume("m", 0.0, 0.0, 6.0, -1.0), "a negative width is a typo");
        assertThrows(IllegalArgumentException.class,
                () -> new TriggerVolume("m", Double.NaN, 0.0, 6.0, 6.0), "NaN geometry is a caller bug");

        // A NaN pose must not answer "no overlap" — that looks exactly like a mechanism that never picks
        // anything up, and would hide a broken plant instead of reporting it.
        TriggerVolume mouth = frontMouth();
        assertThrows(IllegalArgumentException.class,
                () -> mouth.overlapsPiece(Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0), "NaN robot x");
        assertThrows(IllegalArgumentException.class,
                () -> mouth.overlapsPiece(0.0, 0.0, 0.0, 0.0, 0.0, -1.0), "negative piece radius");
    }

    @Test
    void theGeometryItWasGivenIsTheGeometryItReportsBack() {
        // Straightforward accessor pin: a season layer reads these back to draw the mouth, so a swapped
        // depth/width would show up as a mouth drawn sideways rather than as a failing overlap test.
        TriggerVolume mouth = new TriggerVolume(" intake ", 8.0, -2.0, 6.0, 16.0);
        assertEquals("intake", mouth.getName(), "the name is trimmed but otherwise kept");
        assertEquals(8.0, mouth.getForwardOffsetInches(), 0.0);
        assertEquals(-2.0, mouth.getLeftOffsetInches(), 0.0);
        assertEquals(6.0, mouth.getDepthInches(), 0.0);
        assertEquals(16.0, mouth.getWidthInches(), 0.0);
    }
}
