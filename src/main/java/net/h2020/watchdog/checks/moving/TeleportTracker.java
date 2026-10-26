package net.h2020.watchdog.checks.moving;

import java.util.ArrayList;

/**
 * Server teleports awaiting the client's confirmation. Fed by outbound S08 (any thread) and by
 * PlayerTeleportEvent / own set-backs (main thread). Like PlayerConnection.checkMovement, moves are ignored until a
 * position packet matches the teleport target (same x/z, y within 0.1).
 */
final class TeleportTracker {
    private static final class Pending {
        final double x, y, z;
        final long time;
        Pending(double x, double y, double z, long time) { this.x = x; this.y = y; this.z = z; this.time = time; }
    }

    private final ArrayList<Pending> pending = new ArrayList<>();

    synchronized void add(double x, double y, double z) {
        long now = System.currentTimeMillis();
        for (Pending p : pending) {
            if (Math.abs(p.x - x) < 1e-6 && Math.abs(p.y - y) < 1e-6 && Math.abs(p.z - z) < 1e-6 && now - p.time < 3000) return;
        }
        pending.add(new Pending(x, y, z, now));
        while (pending.size() > 16) pending.remove(0);
    }

    /** @return true if this position confirms a pending teleport (it and all older ones are removed). */
    synchronized boolean confirm(double x, double y, double z) {
        for (int i = 0; i < pending.size(); i++) {
            Pending p = pending.get(i);
            if (Math.abs(p.x - x) < 1e-4 && Math.abs(p.z - z) < 1e-4 && Math.abs(p.y - y) < 0.1) {
                pending.subList(0, i + 1).clear();
                return true;
            }
        }
        return false;
    }

    synchronized boolean hasPending(long now, long timeoutMs) {
        while (!pending.isEmpty() && now - pending.get(0).time > timeoutMs) pending.remove(0);
        return !pending.isEmpty();
    }

    synchronized void clear() { pending.clear(); }
}
