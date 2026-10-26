package net.h2020.watchdog.checks.blockbreak;

/**
 * Per-player digging state from C07 packets. A small history of digs is kept because for real players the Netty
 * thread can be a tick ahead of the main thread (the next START may already be in when the previous break event
 * fires). Guarded by the owning PlayerData monitor.
 */
public final class BlockBreakData {
    public static final class Dig {
        public int x, y, z;
        public long startMs, startTick;
        /** Arm swings in the START tick and while digging. */
        public int swings;
        /** STOP_DESTROY_BLOCK arrival, 0 if none yet. */
        public long stopMs;
        public boolean judged;
    }

    private static final int SIZE = 8;
    private final Dig[] digs = new Dig[SIZE];
    private int head;

    /** Between START_DESTROY_BLOCK and STOP/ABORT. */
    public boolean digging;

    /** Instant break without swing, awaiting the end of its client tick. */
    public long noSwingPendingTick = Long.MIN_VALUE / 2;
    public boolean noSwingPending;

    /** NCP FastBreak contention buffer (ms). */
    public double fastBreakBuffer = FastBreak.BUFFER;

    public Dig start(int x, int y, int z, long now, long tick, int swings) {
        Dig d = digs[head];
        if (d == null) { d = new Dig(); digs[head] = d; }
        d.x = x; d.y = y; d.z = z;
        d.startMs = now; d.startTick = tick; d.swings = swings; d.stopMs = 0; d.judged = false;
        head = (head + 1) % SIZE;
        digging = true;
        return d;
    }

    /** Most recent dig (null if none). */
    public Dig current() { return digs[(head - 1 + SIZE) % SIZE]; }

    /** Most recent dig of this block, or null. */
    public Dig find(int x, int y, int z) {
        for (int i = 0; i < SIZE; i++) {
            Dig d = digs[(head - 1 - i + SIZE) % SIZE];
            if (d == null) return null;
            if (d.x == x && d.y == y && d.z == z) return d;
        }
        return null;
    }
}
