package org.horizon36596.simloop.viz;

/**
 * An immutable 3-D vector in the <b>robot-local frame</b>: {@code +X} robot-right, {@code +Y} robot-forward,
 * {@code +Z} up, with the origin at the centre of the drivetrain footprint at floor level
 * ({@code docs/phase1/01-conventions.md} §7, extended to 3-D for CAD in B12).
 *
 * <p>Lengths are <b>inches</b>. The same type is also used for unit direction vectors, which are unitless —
 * {@link #normalized()} is how you get one, and {@link #isUnitLength()} is how you check you were given one.
 *
 * <p>This exists instead of a bare {@code double[3]} so that a reader of {@link MechanismSketch} can see
 * which axis is which without counting array indices (CLAUDE.md rule 12).
 */
public final class RobotVector3 {

    /** Robot-right component. Inches for a position, unitless for a direction. */
    public final double x;

    /** Robot-forward component. Inches for a position, unitless for a direction. */
    public final double y;

    /** Up component. Inches for a position, unitless for a direction. */
    public final double z;

    /**
     * Builds a vector in the robot frame from its three components.
     *
     * @param x robot-right component; inches for a position, unitless for a direction
     * @param y robot-forward component; inches for a position, unitless for a direction
     * @param z up component; inches for a position, unitless for a direction
     * @throws IllegalArgumentException if any component is NaN or infinite — a non-finite component would
     *                                  survive every later comparison and silently poison a drawn pose.
     */
    public RobotVector3(double x, double y, double z) {
        this.x = requireFinite(x, "x");
        this.y = requireFinite(y, "y");
        this.z = requireFinite(z, "z");
    }

    /**
     * The origin, {@code (0, 0, 0)}.
     *
     * @return a vector whose three components are all zero
     */
    public static RobotVector3 origin() {
        return new RobotVector3(0.0, 0.0, 0.0);
    }

    /**
     * Euclidean length.
     *
     * @return the length in inches for a position, unitless for a direction; never negative
     */
    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * Scalar (dot) product with {@code other}. Projecting a position onto a unit axis gives a coordinate.
     *
     * @param other the vector to project onto
     * @return the dot product; inches when one side is a position and the other a unit direction
     */
    public double dot(RobotVector3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    /**
     * Vector (cross) product {@code this × other}, right-handed. Crossing the two canvas axes gives the
     * canvas's <b>normal</b> — the one axis a mechanism drawn on that canvas is allowed to rotate about,
     * which is how {@link MechanismSketch} tells a drawable pivot from a turret yaw.
     *
     * @param other the right-hand side of {@code this × other}
     * @return a vector perpendicular to both, in the robot frame
     */
    public RobotVector3 cross(RobotVector3 other) {
        return new RobotVector3(
                y * other.z - z * other.y,
                z * other.x - x * other.z,
                x * other.y - y * other.x);
    }

    /**
     * This vector scaled to length 1.
     *
     * @return a unitless direction pointing the same way as this vector
     * @throws IllegalArgumentException if this vector has zero length — there is no direction to return,
     *                                  and returning {@code (0,0,0)} would draw a mechanism with no axis.
     */
    public RobotVector3 normalized() {
        double magnitude = length();
        if (magnitude == 0.0) {
            throw new IllegalArgumentException("cannot normalize a zero-length vector — it has no direction");
        }
        return new RobotVector3(x / magnitude, y / magnitude, z / magnitude);
    }

    /**
     * True if this vector is within {@code 1e-6} of unit length. Used to reject a direction that was
     * supplied un-normalized, which would scale every drawn length by an invisible factor.
     *
     * @return true when {@link #length()} is within {@code 1e-6} of 1
     */
    public boolean isUnitLength() {
        return Math.abs(length() - 1.0) <= 1.0e-6;
    }

    /**
     * True if this vector is perpendicular to {@code other} within {@code 1e-6}. The two canvas axes must
     * be perpendicular or the projected drawing is sheared.
     *
     * @param other the vector to compare against
     * @return true when {@link #dot(RobotVector3)} is within {@code 1e-6} of zero
     */
    public boolean isPerpendicularTo(RobotVector3 other) {
        return Math.abs(dot(other)) <= 1.0e-6;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }

    private static double requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
        return value;
    }
}
