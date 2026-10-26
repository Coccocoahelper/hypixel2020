package net.h2020.watchdog.framework;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NCP-style action list, parsed from strings like
 * {@code "cancel vl>5 cancel log:0:3 alert:0:3 vl>35 cancel log alert ban"}.
 * <ul>
 *   <li>Actions before the first {@code vl>N} apply for any violation level.</li>
 *   <li>{@code vl>N} starts a new tier; for a given VL only the highest matching tier is executed (as in NCP).</li>
 *   <li>{@code log}/{@code alert} accept {@code :delay:repeat} — delay = number of triggers swallowed before the first
 *   execution, repeat = minimum seconds between executions (per player and check).</li>
 *   <li>Known actions: cancel, log, alert, kick, ban.</li>
 * </ul>
 */
public final class ActionList {
    public enum Kind { CANCEL, LOG, ALERT, KICK, BAN }

    public static final class Action {
        public final Kind kind;
        public final int delay;
        public final long repeatMs;
        /** Unique index inside this list, used for per-player execution counters. */
        public final int id;

        Action(Kind kind, int delay, long repeatMs, int id) {
            this.kind = kind; this.delay = delay; this.repeatMs = repeatMs; this.id = id;
        }

        @Override public String toString() {
            if (kind == Kind.LOG || kind == Kind.ALERT) return kind.name().toLowerCase() + ":" + delay + ":" + (repeatMs / 1000);
            return kind.name().toLowerCase();
        }
    }

    public static final class Tier {
        public final double threshold;
        public final List<Action> actions;
        Tier(double threshold, List<Action> actions) { this.threshold = threshold; this.actions = Collections.unmodifiableList(actions); }

        public boolean has(Kind k) {
            for (Action a : actions) if (a.kind == k) return true;
            return false;
        }
    }

    private final List<Tier> tiers;
    private final int actionCount;
    private final String source;

    private ActionList(List<Tier> tiers, int actionCount, String source) {
        this.tiers = tiers; this.actionCount = actionCount; this.source = source;
    }

    public static ActionList parse(String s) {
        List<Tier> tiers = new ArrayList<Tier>();
        double threshold = Double.NEGATIVE_INFINITY;
        List<Action> current = new ArrayList<Action>();
        int id = 0;
        for (String raw : s.trim().split("\\s+")) {
            String tok = raw.trim().toLowerCase();
            if (tok.isEmpty()) continue;
            if (tok.startsWith("vl>")) {
                if (!current.isEmpty() || threshold != Double.NEGATIVE_INFINITY) tiers.add(new Tier(threshold, current));
                current = new ArrayList<Action>();
                try { threshold = Double.parseDouble(tok.substring(3)); } catch (NumberFormatException e) { threshold = 0; }
                continue;
            }
            String[] parts = tok.split(":");
            Kind kind;
            try { kind = Kind.valueOf(parts[0].toUpperCase()); } catch (IllegalArgumentException e) { continue; }
            int delay = 0;
            long repeat = 0;
            if (parts.length > 1) try { delay = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
            if (parts.length > 2) try { repeat = (long) (Double.parseDouble(parts[2]) * 1000); } catch (NumberFormatException ignored) {}
            current.add(new Action(kind, delay, repeat, id++));
        }
        tiers.add(new Tier(threshold, current));
        return new ActionList(tiers, id, s.trim());
    }

    /** Highest tier whose threshold is below vl (the base tier applies to any vl). Never null. */
    public Tier tierFor(double vl) {
        Tier best = null;
        for (Tier t : tiers) {
            if (vl > t.threshold || t.threshold == Double.NEGATIVE_INFINITY) {
                if (best == null || t.threshold >= best.threshold) best = t;
            }
        }
        return best != null ? best : new Tier(0, new ArrayList<Action>());
    }

    /** Lowest VL at which a ban tier starts (Double.MAX_VALUE if none). */
    public double banThreshold() {
        double min = Double.MAX_VALUE;
        for (Tier t : tiers) if (t.has(Kind.BAN)) min = Math.min(min, t.threshold);
        return min;
    }

    /** Lowest VL at which a cancel action applies. */
    public double cancelThreshold() {
        double min = Double.MAX_VALUE;
        for (Tier t : tiers) if (t.has(Kind.CANCEL)) min = Math.min(min, t.threshold == Double.NEGATIVE_INFINITY ? 0 : t.threshold);
        return min;
    }

    public int actionCount() { return actionCount; }

    @Override public String toString() { return source; }
}
