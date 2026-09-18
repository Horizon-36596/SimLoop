package org.horizon36596.simloop.plant;

/**
 * The surface shared by every plant in this package that models <b>exactly one</b> degree of freedom:
 * {@link Mechanism1DofPlant} (power-driven, through a motor or a continuous-rotation servo) and
 * {@link PositionalServoPlant} (command-driven, through a positional servo).
 *
 * <p><b>Why this interface exists.</b> A real subsystem often has several degrees of freedom at once —
 * V0's {@code Deposit} has four: a slide that extends, a pivot that aims, an ejector that spins and a
 * latch that opens. {@link MultiDofMechanismPlant} simulates such a subsystem by holding one of these
 * plants per degree of freedom, and it needs a single surface it can tick and read without caring which
 * kind of actuator is underneath. That is the whole job of this interface — it adds no behaviour of its
 * own and it is <b>not</b> an extension point: a new kind of degree of freedom is a new plant class in
 * this package, reviewed like any other (BACKLOG B13).
 *
 * <p><b>A plant that implements this must stay ignorant of every other plant.</b> Nothing here hands one
 * plant a reference to another, and none of the implementations takes one. A degree of freedom inside a
 * composite therefore moves exactly as the same plant would move alone, which is the property
 * {@code MultiDofMechanismPlantTest} pins tick-for-tick (B13 Contract clause 1).
 *
 * <p><b>Units.</b> Each implementation works in its own mechanism unit — inches for a linear slide,
 * radians for an arm pivot, output revolutions for a continuous-rotation ejector — and the composite
 * records that unit's name alongside the degree of freedom so a log key can say which it is. This
 * interface itself is unit-agnostic, exactly as the two plants already are.
 *
 * <p><b>Determinism (domain R5).</b> State changes only inside {@link #update(double)}, off the injected
 * tick, never a wall clock; {@link #getPosition()} and {@link #getVelocity()} are pure reads.
 */
public interface OneDofPlant {

    /**
     * Advance this degree of freedom by {@code deltaTime} seconds, reading whatever command its own
     * actuator currently holds. The only method that mutates state.
     *
     * @param deltaTime the tick length, in seconds
     */
    void update(double deltaTime);

    /** {@return the current position of this degree of freedom, in its own mechanism unit} */
    double getPosition();

    /**
     * Current modelled speed of this degree of freedom, in its own mechanism unit per second.
     * <b>Modelled internal state for tests, drawing and telemetry</b> — see each implementation's javadoc
     * for whether any real sensor on the robot could read it.
     *
     * @return the modelled speed, in this degree of freedom's own mechanism unit per second
     */
    double getVelocity();
}
