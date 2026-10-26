package net.h2020.watchdog.checks.moving;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * NCP-style velocity entries. Two kinds as the 1.8 client applies them:
 * - SET (S12 entity velocity for the player itself / PlayerVelocityEvent): handleEntityVelocity replaces motion;
 * - ADD (S27 explosion knockback): handleExplosion adds to motion.
 * An entry can only explain moves the client sent after it was sent (packet sequence), and expires after a
 * latency-dependent number of movement packets.
 */
final class VelocityTracker {
    static final class Entry {
        final int id;
        final boolean additive;
        final double x, y, z, h;
        /** Inbound flying sequence at the time the velocity was sent. */
        final long seq;
        final long time;
        /** Sequence of the move that consumed it (0 = unused); kept usable for a few more ticks. */
        long usedSeq;

        Entry(int id, boolean additive, double x, double y, double z, long seq, long time) {
            this.id = id; this.additive = additive; this.x = x; this.y = y; this.z = z;
            this.h = Math.sqrt(x * x + z * z); this.seq = seq; this.time = time;
        }
    }

    private final ArrayList<Entry> entries = new ArrayList<>();
    private int nextId = 1;

    synchronized Entry add(boolean additive, double x, double y, double z, long seq) {
        long now = System.currentTimeMillis();
        for (Entry e : entries) {
            // PlayerVelocityEvent and the S12 that follows it describe the same velocity.
            if (e.additive == additive && now - e.time < 250 && Math.abs(e.x - x) < 0.01
                    && Math.abs(e.y - y) < 0.01 && Math.abs(e.z - z) < 0.01) return null;
        }
        Entry e = new Entry(nextId++, additive, x, y, z, seq, now);
        entries.add(e);
        while (entries.size() > 24) entries.remove(0);
        return e;
    }

    /** Entries that may explain the move with sequence {@code seq}. */
    synchronized List<Entry> usable(long seq, long maxPackets) {
        long now = System.currentTimeMillis();
        List<Entry> out = new ArrayList<>(2);
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if ((e.usedSeq != 0 && seq - e.usedSeq > 3) || (seq - e.seq > maxPackets && now - e.time > 1000)
                    || now - e.time > 6000) { it.remove(); continue; }
            if (e.seq < seq) out.add(e);
        }
        return out;
    }

    /** Marks an entry as consumed by a move; older SET entries are superseded and dropped as well. */
    synchronized void consume(Entry used, long seq) {
        if (used.usedSeq == 0) used.usedSeq = seq;
        if (!used.additive) {
            for (Entry e : entries) if (!e.additive && e.id < used.id && e.usedSeq == 0) e.usedSeq = seq;
        }
    }

    /** Newest SET entry with id greater than {@code afterId}, or null. */
    synchronized Entry newestSet(int afterId) {
        Entry best = null;
        for (Entry e : entries) if (!e.additive && e.id > afterId && (best == null || e.id > best.id)) best = e;
        return best;
    }

    synchronized boolean hasAdditiveSince(long seq) {
        for (Entry e : entries) if (e.additive && e.seq >= seq) return true;
        return false;
    }

    synchronized void clear() { entries.clear(); }
}
