package org.horizon36596.simloop.field;

/**
 * One game piece in the sim: a field position in inches, a physical size, and exactly one
 * {@link PossessionState}.
 *
 * <p><b>Frame and units.</b> {@link #getFieldXInches()} / {@link #getFieldYInches()} are in the repo's
 * FTC-Cartesian field frame — <b>+X right, +Y forward, origin at the centre of the field</b>, inches, so
 * the reachable field is {@code x, y in [-72, +72]} (conventions §7). {@link #getRadiusInches()} is the
 * piece's own radius in inches; it exists so a trigger volume can decide whether it <em>overlaps</em> a
 * piece rather than only whether it contains the piece's centre point.
 *
 * <p><b>The tracker owns every change.</b> All the setters are package-private on purpose: a piece is
 * only ever moved or re-stated by {@link GamePieceTracker}, which is what guarantees every transition
 * gets logged (domain R5). Season code holds a {@code GamePiece} as a read-only view and calls the
 * tracker to change anything.
 *
 * <p>Season-agnostic (domain R7): a piece is a circle of some size in some state. Nothing here knows what
 * game it is from, what colour it is, or what it scores — the season layer in {@code TeamCode} supplies
 * the radius and how many of them fit.
 */
public final class GamePiece {

    private final int id;
    private final double radiusInches;

    private double fieldXInches;
    private double fieldYInches;
    private PossessionState state;

    /**
     * Created only by {@link GamePieceTracker#addLoosePiece(double, double, double)}, which validates
     * every argument first and assigns the id.
     */
    GamePiece(int id, double fieldXInches, double fieldYInches, double radiusInches) {
        this.id = id;
        this.fieldXInches = fieldXInches;
        this.fieldYInches = fieldYInches;
        this.radiusInches = radiusInches;
        this.state = PossessionState.LOOSE;
    }

    /**
     * Stable identifier, assigned in the order pieces were added (first piece is {@code 0}). Acquisition
     * scans pieces in this order, so a partly-full robot fills up reproducibly on replay (domain R5).
     *
     * @return the piece's id, unitless, counting from zero
     */
    public int getId() {
        return id;
    }

    /** {@return the field position, inches, FTC-Cartesian +X right (see class javadoc for the frame)} */
    public double getFieldXInches() {
        return fieldXInches;
    }

    /** {@return the field position, inches, FTC-Cartesian +Y forward (see class javadoc for the frame)} */
    public double getFieldYInches() {
        return fieldYInches;
    }

    
    /** {@return the piece's radius, in inches} - half its diameter, e.g. 1.4 for a 2.8 in ball */
    public double getRadiusInches() {
        return radiusInches;
    }

    /** {@return which of the three possession states this piece is in right now} */
    public PossessionState getState() {
        return state;
    }

    /** {@return true while this piece is being carried by the robot} */
    public boolean isHeld() {
        return state == PossessionState.HELD;
    }

    /** Package-private: only {@link GamePieceTracker} moves a piece, so that the move gets logged. */
    void setFieldPositionInches(double fieldXInches, double fieldYInches) {
        this.fieldXInches = fieldXInches;
        this.fieldYInches = fieldYInches;
    }

    /** Package-private: only {@link GamePieceTracker} restates a piece, so that the change gets logged. */
    void setState(PossessionState state) {
        this.state = state;
    }

    @Override
    public String toString() {
        return "GamePiece#" + id + " " + state;
    }
}
