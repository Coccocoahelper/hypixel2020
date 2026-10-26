package net.h2020.watchdog.framework;

/** Violation level of one player for one check, with lazy NCP-style decay and per-action execution counters. */
public final class ViolationData {
    private double vl;
    private long lastUpdate;
    public long lastFlag;
    public int totalFlags;
    private int[] actionCounts = new int[0];
    private long[] actionLastExec = new long[0];

    /** Applies decay up to now. Caller synchronizes on this object. */
    public double decay(long now, CheckSettings s) {
        if (lastUpdate != 0 && vl > 0) {
            double dt = (now - lastUpdate) / 1000.0;
            if (dt > 0) {
                vl = vl * Math.pow(s.decayFactor, dt) - s.decayLinear * dt;
                if (vl < 0.0001) vl = 0;
            }
        }
        lastUpdate = now;
        return vl;
    }

    public double vl() { return vl; }

    public void add(double amount) { vl = Math.max(0, vl + amount); }

    public void multiply(double f) { vl = Math.max(0, vl * f); }

    public void reset() { vl = 0; for (int i = 0; i < actionCounts.length; i++) actionCounts[i] = 0; }

    /** NCP delay/repeat semantics for log/alert-like actions: returns true if the action should run now. */
    public boolean ready(ActionList.Action a, int listSize, long now) {
        if (actionCounts.length < listSize) {
            int[] c = new int[listSize];
            long[] l = new long[listSize];
            System.arraycopy(actionCounts, 0, c, 0, actionCounts.length);
            System.arraycopy(actionLastExec, 0, l, 0, actionLastExec.length);
            actionCounts = c; actionLastExec = l;
        }
        int count = ++actionCounts[a.id];
        if (count <= a.delay) return false;
        if (a.repeatMs > 0 && now - actionLastExec[a.id] < a.repeatMs) return false;
        actionLastExec[a.id] = now;
        return true;
    }
}
