package net.h2020.watchdog.framework;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lag compensation: remembers every player's hitbox for the last ~1.6 seconds (one snapshot per server tick) so
 * fight checks can test against where the attacker's client actually saw the target (NCP-style "past positions
 * within the attacker's ping"). Records on the main thread; reads are thread-safe.
 */
public final class PositionTracker implements Runnable {
    private static final int SIZE = 32;

    private static final class Ring {
        final long[] time = new long[SIZE];
        final Box[] boxes = new Box[SIZE];
        int head, count;
        String world;

        synchronized void add(long now, Box b, String w) {
            if (world != null && !world.equals(w)) { count = 0; head = 0; } // world change: history is meaningless
            world = w;
            time[head] = now;
            boxes[head] = b;
            head = (head + 1) % SIZE;
            if (count < SIZE) count++;
        }

        synchronized void collect(long since, List<Box> out) {
            for (int i = 0; i < count; i++) {
                int idx = (head - 1 - i + SIZE) % SIZE;
                if (time[idx] < since && i > 0) break;
                out.add(boxes[idx]);
            }
        }
    }

    private final Map<Integer, Ring> byId = new ConcurrentHashMap<Integer, Ring>();
    private final Map<Integer, UUID> idToUuid = new ConcurrentHashMap<Integer, UUID>();
    private final Map<Integer, Player> idToPlayer = new ConcurrentHashMap<Integer, Player>();

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            int id = p.getEntityId();
            Ring r = byId.get(id);
            if (r == null) {
                r = new Ring();
                byId.put(id, r);
                idToUuid.put(id, p.getUniqueId());
                idToPlayer.put(id, p);
            }
            r.add(now, Box.of(((CraftPlayer) p).getHandle().getBoundingBox()), p.getWorld().getName());
        }
    }

    /** Hitboxes of the entity recorded since the given time (newest first, always at least the newest one). */
    public List<Box> boxesSince(int entityId, long since) {
        List<Box> out = new ArrayList<Box>(SIZE);
        Ring r = byId.get(entityId);
        if (r != null) r.collect(since, out);
        return out;
    }

    public UUID uuidOf(int entityId) { return idToUuid.get(entityId); }

    /** Online player with this entity id (thread-safe; avoids Bukkit lookups from Netty threads). */
    public Player playerOf(int entityId) { return idToPlayer.get(entityId); }

    public void remove(Player p) {
        byId.remove(p.getEntityId());
        idToUuid.remove(p.getEntityId());
        idToPlayer.remove(p.getEntityId());
    }

    public void clear() { byId.clear(); idToUuid.clear(); idToPlayer.clear(); }
}
