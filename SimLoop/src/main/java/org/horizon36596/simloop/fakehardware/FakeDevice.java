package org.horizon36596.simloop.fakehardware;

/**
 * A simulated hardware device with a kinematic plant. The harness ticks every fake device once per
 * loop with the {@code FakeTimer}'s deltaTime (fakehardware-plant §2). State mutation happens ONLY
 * in {@link #update(double)}; the SDK getters are pure reads (conventions §4).
 */
public interface FakeDevice {

    /**
     * Advance this device's kinematic state by {@code deltaTime} seconds.
     * Pure and deterministic — no wall-clock reads (domain R5).
     *
     * @param deltaTime how far to advance, in seconds; the harness passes its fixed tick
     */
    void update(double deltaTime);
}
