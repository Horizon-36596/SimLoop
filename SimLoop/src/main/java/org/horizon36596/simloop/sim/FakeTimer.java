package org.horizon36596.simloop.sim;

/**
 * The single, advanceable simulation clock (fakehardware-plant §6, domain R5). The harness advances
 * it by a fixed deltaTime each tick; wire it to PsiKit with {@code Logger.setTimeSource(timer::time)}
 * so log timestamps are deterministic. No wall-clock is ever read on a sim path.
 */
public class FakeTimer {

    private double seconds = 0.0;

    /** Creates a clock reading 0.0 seconds. */
    public FakeTimer() {
    }

    /**
     * Current simulation time. Suitable for {@code Logger.setTimeSource}.
     *
     * @return seconds since this timer was created, monotonically non-decreasing
     */
    public double time() {
        return seconds;
    }

    /**
     * Advance the clock.
     *
     * @param deltaTime how far to advance, in seconds
     */
    public void advance(double deltaTime) {
        seconds += deltaTime;
    }

    /** Reset to zero (e.g. between determinism runs). */
    public void reset() {
        seconds = 0.0;
    }
}
