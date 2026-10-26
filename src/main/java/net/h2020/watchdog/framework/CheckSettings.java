package net.h2020.watchdog.framework;

/** Per-check configuration: enabled flag, NCP-style action list and VL decay. */
public final class CheckSettings {
    public final boolean enabled;
    public final ActionList actions;
    /** Multiplicative decay per second (NCP fight checks decay roughly vl*0.97/s). 1 = none. */
    public final double decayFactor;
    /** Linear decay per second, applied after the multiplicative one. */
    public final double decayLinear;

    public CheckSettings(boolean enabled, ActionList actions, double decayFactor, double decayLinear) {
        this.enabled = enabled; this.actions = actions; this.decayFactor = decayFactor; this.decayLinear = decayLinear;
    }

    public static CheckSettings of(String actions, double decayFactor, double decayLinear) {
        return new CheckSettings(true, ActionList.parse(actions), decayFactor, decayLinear);
    }
}
