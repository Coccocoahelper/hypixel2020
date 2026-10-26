package net.h2020.watchdog.framework;

/**
 * Packet-thread detector for the 2020 "Watchdog" packet criticals (watchdog.movement.legacy-2020-crits-gap): right
 * before a C02 attack the client sends 2-3 extra position packets on the spot it stands on, a tiny rise above that
 * ground (0.0625 / 0.06x) and then a drop back towards it (0 / 1.1E-5), all claiming onGround=false. Vanilla then
 * has !onGround and fallDistance > 0 when it handles the C02, so the hit crits.
 *
 * The burst only counts when every one of these holds:
 * - the last ordinary flying packet claimed ground, and the burst packets carry exactly its x/z (no horizontal move);
 * - each burst packet is 0..{@link #MAX_OFFSET} above that ground packet, the first one rises, the last one is lower
 *   than the peak (that drop is what vanilla turns into fallDistance);
 * - at most {@link #MAX_PACKETS} packets, back to back (within {@link #MAX_GAP_MS}), and the C02 follows within the
 *   same window with no ordinary flying packet in between.
 * Anything else is an ordinary packet and is checked as usual. One instance per consumer per player.
 */
public final class LegacyCritBurst {
    /** Highest offset above the ground packet a burst packet may have (2020 clients used 0.0625 and less). */
    public static final double MAX_OFFSET = 0.07;
    public static final int MAX_PACKETS = 3;
    /** Burst packets and the attack are sent in the same client tick, back to back. */
    public static final long MAX_GAP_MS = 15;

    private boolean known, ground;
    private double x, y, z;
    private int count;
    private double peak, last;
    private long lastTime;

    /**
     * Every flying packet, in order. @return true if the packet is (so far) part of a possible crit burst; false for
     * an ordinary packet, which also ends any burst in progress.
     */
    public boolean onFlying(boolean hasPos, double px, double py, double pz, boolean pGround, long now) {
        if (known && ground && hasPos && !pGround && count < MAX_PACKETS && px == x && pz == z) {
            double off = py - y;
            boolean inWindow = count == 0 || now - lastTime <= MAX_GAP_MS;
            if (inWindow && off >= 0.0 && off <= MAX_OFFSET && (count > 0 || off > 1.0E-6)) {
                count++;
                peak = Math.max(peak, off);
                last = off;
                lastTime = now;
                return true;
            }
        }
        count = 0;
        peak = 0;
        last = 0;
        if (hasPos) {
            x = px;
            y = py;
            z = pz;
            known = true;
        }
        ground = pGround;
        return false;
    }

    /**
     * C02 ATTACK. @return the number of burst packets right before it when they form a complete crit burst, else 0.
     * Consumes the burst (a second attack in the same tick needs its own).
     */
    public int onAttack(long now) {
        int n = count >= 2 && last < peak - 1.0E-4 && now - lastTime <= MAX_GAP_MS ? count : 0;
        count = 0;
        peak = 0;
        last = 0;
        return n;
    }

    /** Teleport / respawn: the next ordinary packet establishes a new base. */
    public void reset() {
        known = false;
        count = 0;
        peak = 0;
        last = 0;
    }
}
