package net.h2020.watchdog.checks.moving;

import net.minecraft.server.v1_8_R3.AxisAlignedBB;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client/server world desync near a player, learned from outbound block changes (S23/S22/S27):
 * - "solid" cells became passable on the server, but the client may still stand on the old block until the change
 *   arrives (a bridge block broken under you, a SkyWars cage removed, a rejected placement reverted);
 * - "air" cells became solid on the server, but the client may still walk through them (a placement next to you,
 *   a cancelled break that the server restores).
 * Each cell is trusted for moves that arrive before its expiry (send time + latency allowance).
 */
final class GhostBlocks {
    private final ConcurrentHashMap<Long, Long> solid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> air = new ConcurrentHashMap<>();

    static long key(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }

    void addSolid(int x, int y, int z, long expiry) { put(solid, key(x, y, z), expiry); }

    void addAir(int x, int y, int z, long expiry) { put(air, key(x, y, z), expiry); }

    private static void put(ConcurrentHashMap<Long, Long> map, long key, long expiry) {
        if (map.size() > 4096) map.clear();
        Long old = map.get(key);
        if (old == null || old < expiry) map.put(key, expiry);
    }

    boolean isAir(int x, int y, int z, long time) {
        if (air.isEmpty()) return false;
        Long e = air.get(key(x, y, z));
        return e != null && time <= e;
    }

    /** True if a ghost-solid cell (treated as a full cube) intersects the box. */
    boolean solidIntersects(AxisAlignedBB bb, long time) {
        if (solid.isEmpty()) return false;
        int x0 = floor(bb.a), x1 = floor(bb.d), y0 = floor(bb.b), y1 = floor(bb.e), z0 = floor(bb.c), z1 = floor(bb.f);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    Long e = solid.get(key(x, y, z));
                    if (e != null && time <= e) {
                        // Strict overlap like vanilla collision boxes.
                        if (bb.d > x && bb.a < x + 1 && bb.e > y && bb.b < y + 1 && bb.f > z && bb.c < z + 1) return true;
                    }
                }
            }
        }
        return false;
    }

    void cleanup(long now) {
        prune(solid, now);
        prune(air, now);
    }

    private static void prune(ConcurrentHashMap<Long, Long> map, long now) {
        if (map.isEmpty()) return;
        Iterator<Map.Entry<Long, Long>> it = map.entrySet().iterator();
        while (it.hasNext()) if (it.next().getValue() + 5000 < now) it.remove();
    }

    void clear() { solid.clear(); air.clear(); }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
