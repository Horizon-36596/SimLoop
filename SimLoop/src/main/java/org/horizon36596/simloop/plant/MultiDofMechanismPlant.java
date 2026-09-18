package org.horizon36596.simloop.plant;

import org.psilynx.psikit.core.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How one subsystem with several real degrees of freedom is simulated: <b>one plant per degree of
 * freedom, held side by side, ticked together and logged apart</b> (BACKLOG B13, decided 2026-09-14).
 *
 * <p>V0's {@code Deposit} is the shape this exists for. It is deliberately <b>one</b> subsystem in code,
 * because that is how the hardware reads, but it carries four independent things that move:
 *
 * <table border="1">
 *   <caption>The four degrees of freedom of V0's Deposit, and the plant each one gets</caption>
 *   <tr><th>Degree of freedom</th><th>Actuator</th><th>Plant</th><th>Unit</th></tr>
 *   <tr><td>slide extension</td><td>2 motors driving one rig</td><td>{@link Mechanism1DofPlant}</td>
 *       <td>inches</td></tr>
 *   <tr><td>pivot angle</td><td>1 positional servo</td><td>{@link PositionalServoPlant}</td>
 *       <td>radians</td></tr>
 *   <tr><td>ejector travel</td><td>2 continuous-rotation servos</td>
 *       <td>{@link Mechanism1DofPlant} with no end limits</td><td>revolutions</td></tr>
 *   <tr><td>latch</td><td>1 positional servo</td><td>{@link PositionalServoPlant}</td>
 *       <td>radians</td></tr>
 * </table>
 *
 * <h2>What this class does, and the three things it deliberately does not</h2>
 * It <b>names</b> each degree of freedom, <b>ticks</b> them all from one call, and <b>logs</b> each under
 * its own key. That is the whole class. In particular:
 *
 * <ul>
 *   <li><b>It does no physics of its own.</b> Every number it reports comes straight out of one of the
 *       plants above. A degree of freedom inside a composite moves exactly as the same plant would move
 *       alone, tick for tick — pinned by {@code MultiDofMechanismPlantTest} (B13 Contract clause 1).</li>
 *   <li><b>It never lets one plant see another.</b> Nothing here hands a plant a reference to a sibling,
 *       and none of the plant classes accepts one. Coupling between degrees of freedom — a heavy slide
 *       loading the pivot that carries it — is rigid-body physics, which domain R6 rules out. If a
 *       mechanism ever appears to need it, stop and flag it rather than adding it here.</li>
 *   <li><b>It does not build the plants.</b> The season layer constructs each one, keeps its own typed
 *       reference (the slide's glue still needs {@code Mechanism1DofPlant.getVelocityFractionOfMaxSpeed()}
 *       to feed its motor's current model, which is not on the shared {@link OneDofPlant} surface), and
 *       registers it here. Construction staying outside is what keeps this class free of any knowledge
 *       about which actuator is underneath (domain R7).</li>
 * </ul>
 *
 * <h2>Using it</h2>
 * <pre>
 *   MultiDofMechanismPlant deposit = MultiDofMechanismPlant.builder()
 *           .addDof("slide",   slidePlant,   "In")    // inches
 *           .addDof("pivot",   pivotPlant,   "Rad")   // radians
 *           .addDof("ejector", ejectorPlant, "Rev")   // output revolutions
 *           .addDof("latch",   latchPlant,   "Rad")
 *           .build();
 *
 *   // once per sim tick:
 *   deposit.update(deltaTime);
 *   deposit.record("DepositPlant");
 * </pre>
 *
 * <h2>What it logs</h2>
 * Two keys per degree of freedom, both carrying the unit in the key name as the logging contract requires
 * (rule 2), so a reader in AdvantageScope can tell which degree of freedom a number belongs to and what
 * it is measured in without opening any code:
 * <pre>
 *   &lt;prefix&gt;/&lt;dof&gt;/position&lt;Unit&gt;            e.g. DepositPlant/slide/positionIn
 *   &lt;prefix&gt;/&lt;dof&gt;/velocity&lt;Unit&gt;PerSecond    e.g. DepositPlant/slide/velocityInPerSecond
 * </pre>
 * These are <b>sim-side truth</b>, not the subsystem's own telemetry: they are what the model believes,
 * logged next to what the robot's own sensors reported so the two can be compared. The subsystem keeps
 * logging its own {@code output} / {@code state} / {@code faults} and its position keys under its own
 * name, exactly as {@code docs/process/logging-contract.md} rule 3 requires — this class does not
 * substitute for that and does not log those fields. Same standing as {@code GamePieceTracker}: sim
 * bookkeeping, so the mechanism field set does not apply to it.
 *
 * <p><b>Season-agnostic core</b> (domain R7, SimLoop rule 1): it knows nothing about a Deposit, a slide or
 * a pollen. Every name and unit above arrives from the caller. <b>Deterministic</b> (domain R5): state
 * changes only in {@link #update(double)} off the injected tick, the degrees of freedom are ticked in
 * registration order every time, and {@link #record(String)} is a pure read.
 */
public final class MultiDofMechanismPlant {

    /** One named degree of freedom: the plant that moves it, and what its numbers are measured in. */
    private static final class Dof {
        private final String name;
        private final OneDofPlant plant;
        private final String positionUnitSuffix;

        private Dof(String name, OneDofPlant plant, String positionUnitSuffix) {
            this.name = name;
            this.plant = plant;
            this.positionUnitSuffix = positionUnitSuffix;
        }
    }

    // Registration order, kept: the ticking order is part of the deterministic replay (domain R5), and a
    // log tree that lists the degrees of freedom in the order the human declared them reads better than
    // one sorted alphabetically.
    private final List<Dof> dofsInOrder;
    private final Map<String, Dof> dofsByName;

    private MultiDofMechanismPlant(Builder builder) {
        this.dofsInOrder = Collections.unmodifiableList(new ArrayList<Dof>(builder.dofs));
        Map<String, Dof> byName = new LinkedHashMap<String, Dof>();
        for (Dof dof : this.dofsInOrder) {
            byName.put(dof.name, dof);
        }
        this.dofsByName = Collections.unmodifiableMap(byName);
    }

    /**
     * Start describing a multi-degree-of-freedom mechanism. At least one degree of freedom is required.
     *
     * @return a builder with no degrees of freedom added yet
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Advance every degree of freedom by {@code deltaTime} seconds, in registration order.
     *
     * <p>Each plant reads its own actuator and nothing else, so the order is invisible in the result —
     * it is fixed anyway, because a replay that ticked in a different order would be a different run
     * (domain R5).
     *
     * @param deltaTime the tick length, in seconds
     */
    public void update(double deltaTime) {
        for (Dof dof : dofsInOrder) {
            dof.plant.update(deltaTime);
        }
    }

    /**
     * Write every degree of freedom's modelled position and velocity to the log, under
     * {@code <keyPrefix>/<dof>/...}. Pure read — call it once per tick, at the bottom.
     *
     * @param keyPrefix the mechanism's log-key root, e.g. {@code "DepositPlant"}. Non-blank. Naming it
     *                  distinctly from the subsystem's own prefix keeps sim truth and robot telemetry
     *                  apart in the log tree, where a reader can hold them side by side.
     * @throws IllegalArgumentException if {@code keyPrefix} is null or blank.
     */
    public void record(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("multi-DOF plant log key prefix must be a non-blank string");
        }
        String prefix = keyPrefix.trim();
        for (Dof dof : dofsInOrder) {
            String dofPrefix = prefix + "/" + dof.name + "/";
            Logger.recordOutput(dofPrefix + "position" + dof.positionUnitSuffix, dof.plant.getPosition());
            Logger.recordOutput(
                    dofPrefix + "velocity" + dof.positionUnitSuffix + "PerSecond", dof.plant.getVelocity());
        }
    }

    /**
     * The plant moving one named degree of freedom, for a caller that needs to read more than
     * {@link #getPosition(String)} and {@link #getVelocity(String)} give it.
     *
     * @param name the degree of freedom's registered name
     * @return the plant registered under that name
     * @throws IllegalArgumentException if no degree of freedom is registered under that name.
     */
    public OneDofPlant getDof(String name) {
        return require(name).plant;
    }

    /**
     * Current position of one named degree of freedom.
     *
     * @param name the degree of freedom's registered name
     * @return the position, in that degree of freedom's own mechanism unit
     */
    public double getPosition(String name) {
        return require(name).plant.getPosition();
    }

    /**
     * Current modelled speed of one named degree of freedom.
     *
     * @param name the degree of freedom's registered name
     * @return the modelled speed, in that degree of freedom's own mechanism unit per second
     */
    public double getVelocity(String name) {
        return require(name).plant.getVelocity();
    }

    /** {@return every degree of freedom's name, in registration order} */
    public List<String> getDofNames() {
        List<String> names = new ArrayList<String>(dofsInOrder.size());
        for (Dof dof : dofsInOrder) {
            names.add(dof.name);
        }
        return Collections.unmodifiableList(names);
    }

    /** {@return how many degrees of freedom this mechanism has} */
    public int getDofCount() {
        return dofsInOrder.size();
    }

    private Dof require(String name) {
        Dof dof = dofsByName.get(name);
        if (dof == null) {
            throw new IllegalArgumentException(
                    "no degree of freedom named \"" + name + "\"; this mechanism has " + getDofNames());
        }
        return dof;
    }

    /** Collects the degrees of freedom, in the order they are added. */
    public static final class Builder {

        private final List<Dof> dofs = new ArrayList<Dof>();

        private Builder() {
        }

        /**
         * Add one degree of freedom.
         *
         * @param name               what this degree of freedom is called, e.g. {@code "slide"}. Becomes
         *                           one segment of its log key, so it must be non-blank and must not
         *                           contain {@code '/'} — a slash would fabricate an extra level of
         *                           nesting and the key would quietly land somewhere else in the tree.
         *                           Names must be unique within one mechanism.
         * @param plant              the plant that moves it — a {@link Mechanism1DofPlant} for anything
         *                           power-driven, a {@link PositionalServoPlant} for anything commanded
         *                           to a position. Already constructed by the season layer, which keeps
         *                           its own typed reference.
         * @param positionUnitSuffix the unit of this degree of freedom's position, capitalised for a log
         *                           key: {@code "In"} for inches, {@code "Rad"} for radians, {@code "Rev"}
         *                           for output revolutions. It is required rather than defaulted because
         *                           a log key is read in AdvantageScope with no javadoc attached, so the
         *                           key is the only place the unit can live
         *                           ({@code docs/process/logging-contract.md} rule 2).
         * @throws IllegalArgumentException if any argument is null or blank, the name contains
         *                                  {@code '/'}, the name is already used, or {@code plant} is an
         *                                  instance already registered under another name (which would
         *                                  tick it twice per update).
         * @return this builder
         */
        public Builder addDof(String name, OneDofPlant plant, String positionUnitSuffix) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("degree-of-freedom name must be a non-blank string");
            }
            String trimmedName = name.trim();
            if (trimmedName.contains("/")) {
                throw new IllegalArgumentException(
                        "degree-of-freedom name must not contain '/', got \"" + trimmedName + "\"");
            }
            if (plant == null) {
                throw new IllegalArgumentException(
                        "degree of freedom \"" + trimmedName + "\" needs a plant to move it");
            }
            if (positionUnitSuffix == null || positionUnitSuffix.trim().isEmpty()) {
                throw new IllegalArgumentException("degree of freedom \"" + trimmedName
                        + "\" needs a position unit suffix for its log key, e.g. \"In\"");
            }
            String trimmedSuffix = positionUnitSuffix.trim();
            if (trimmedSuffix.contains("/")) {
                throw new IllegalArgumentException(
                        "position unit suffix must not contain '/', got \"" + trimmedSuffix + "\"");
            }
            for (Dof existing : dofs) {
                if (existing.name.equals(trimmedName)) {
                    throw new IllegalArgumentException("degree of freedom \"" + trimmedName
                            + "\" is already registered; names must be unique within one mechanism");
                }
                // The same plant under two names is a copy-paste mistake, and a silent one: update()
                // would tick that plant TWICE per sim tick, so it would move at double speed while the
                // degree of freedom that was meant to be there never moved at all, and both log keys
                // would carry the same number. Nothing downstream could tell that from a real reading.
                if (existing.plant == plant) {
                    throw new IllegalArgumentException("the plant registered as \"" + existing.name
                            + "\" is being registered again as \"" + trimmedName + "\"; one plant models "
                            + "one degree of freedom, and registering it twice would tick it twice per "
                            + "update and log the same number under both names");
                }
            }
            dofs.add(new Dof(trimmedName, plant, trimmedSuffix));
            return this;
        }

        /**
         * Produces the immutable multi-DOF plant.
         *
         * @return the plant, ticking every registered degree of freedom in registration order
         * @throws IllegalArgumentException if no degree of freedom was added — a mechanism with none is a
         *                                  wiring mistake, and it would silently log nothing.
         */
        public MultiDofMechanismPlant build() {
            if (dofs.isEmpty()) {
                throw new IllegalArgumentException(
                        "a multi-DOF mechanism needs at least one degree of freedom");
            }
            return new MultiDofMechanismPlant(this);
        }
    }
}
