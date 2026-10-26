package net.h2020.watchdog.framework;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.checks.blockbreak.BlockBreakData;
import net.h2020.watchdog.checks.blockplace.BlockPlaceData;
import net.h2020.watchdog.checks.fight.FightData;
import net.h2020.watchdog.checks.inventory.InventoryData;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything Watchdog knows about one player. Packet-derived state is mutated under {@code synchronized(this)}
 * (packets of humans arrive on the Netty thread, bots on the main thread); main-thread listeners also lock this
 * object when they touch check data. Fields read cross-player are volatile.
 */
public final class PlayerData {
    public final UUID uuid;
    public final String name;

    private final Map<CheckType, ViolationData> vls = new EnumMap<CheckType, ViolationData>(CheckType.class);
    private final Map<CheckType, Long> exemptions = new ConcurrentHashMap<CheckType, Long>();
    public volatile long reportedUntil;
    public final Set<UUID> reporters = ConcurrentHashMap.newKeySet();

    // ---- rotation (client-sent) ----
    public float yaw, pitch, prevYaw, prevPitch;
    public boolean hasRotation;
    private static final int ROT = 8;
    private final float[] rYaw = new float[ROT], rPitch = new float[ROT];
    private final long[] rTime = new long[ROT];
    private int rHead, rCount;
    /** Rotation samples to skip for heuristics (after teleports / respawns the delta is server-forced). */
    public int skipRotationSamples;

    // ---- position (client-sent) ----
    public double x, y, z, prevX, prevY, prevZ;
    public boolean hasPosition;
    public boolean onGround, prevOnGround;
    /** y of the last flying packet that claimed onGround, and the highest y reached since. */
    public double lastGroundY, maxYSinceGround;
    public int airTicks;
    public double deltaXZ, prevDeltaXZ, deltaY;
    /** 2020 packet-crit burst detector (legacy-2020-crits-gap), fed with every flying packet. */
    public final LegacyCritBurst critBurst = new LegacyCritBurst();

    // ---- per client tick counters (reset after each flying packet) ----
    public long flyingCount;
    public int swingsThisTick, attacksThisTick, placesThisTick;
    /** Set by a handler that "used" the current C0A (block place/dig) so fight click analysis skips it. */
    public boolean swingConsumed;

    public volatile long lastFlyingMs, lastPacketMs, lastAttackMs;
    public volatile long lastVelocityMs, lastTeleportMs;
    public volatile boolean sprinting, sneaking;
    public volatile long joinMs = System.currentTimeMillis();

    public final FightData fight = new FightData();
    public final BlockPlaceData place = new BlockPlaceData();
    public final BlockBreakData dig = new BlockBreakData();
    public final InventoryData inv = new InventoryData();

    public PlayerData(UUID uuid, String name) { this.uuid = uuid; this.name = name; }

    public ViolationData vl(CheckType t) {
        synchronized (vls) {
            ViolationData v = vls.get(t);
            if (v == null) { v = new ViolationData(); vls.put(t, v); }
            return v;
        }
    }

    public ViolationData vlIfPresent(CheckType t) { synchronized (vls) { return vls.get(t); } }

    /** Checks this player has violation data for. */
    public CheckType[] checksWithData() {
        synchronized (vls) { return vls.keySet().toArray(new CheckType[0]); }
    }

    /** check -> exempt-until (ms). */
    public Map<CheckType, Long> exemptions() { return exemptions; }

    public void resetViolations() {
        synchronized (vls) { for (ViolationData v : vls.values()) synchronized (v) { v.reset(); } }
    }

    // ---- packet state updates (called by PacketRouter under lock) ----

    void onFlying(boolean hasPos, double px, double py, double pz, boolean hasLook, float pyaw, float ppitch,
                  boolean ground, long now) {
        flyingCount++;
        lastFlyingMs = now;
        critBurst.onFlying(hasPos, px, py, pz, ground, now);
        prevOnGround = onGround;
        onGround = ground;
        if (hasLook) {
            prevYaw = hasRotation ? yaw : pyaw;
            prevPitch = hasRotation ? pitch : ppitch;
            yaw = pyaw;
            pitch = ppitch;
            hasRotation = true;
        } else {
            prevYaw = yaw;
            prevPitch = pitch;
        }
        rYaw[rHead] = yaw;
        rPitch[rHead] = pitch;
        rTime[rHead] = now;
        rHead = (rHead + 1) % ROT;
        if (rCount < ROT) rCount++;

        if (hasPos) {
            if (!hasPosition) { x = px; y = py; z = pz; lastGroundY = py; maxYSinceGround = py; }
            prevX = x; prevY = y; prevZ = z;
            x = px; y = py; z = pz;
            hasPosition = true;
        } else {
            prevX = x; prevY = y; prevZ = z;
        }
        prevDeltaXZ = deltaXZ;
        double dx = x - prevX, dz = z - prevZ;
        deltaXZ = Math.sqrt(dx * dx + dz * dz);
        deltaY = y - prevY;
        if (ground) {
            lastGroundY = y;
            maxYSinceGround = y;
            airTicks = 0;
        } else {
            airTicks++;
            if (y > maxYSinceGround) maxYSinceGround = y;
        }
    }

    void resetTick() {
        swingsThisTick = 0;
        attacksThisTick = 0;
        placesThisTick = 0;
    }

    /** Teleport/respawn: forget position-derived state so heuristics don't see a jump. */
    public void onTeleport(long now) {
        lastTeleportMs = now;
        skipRotationSamples = 3;
        hasPosition = false;
        critBurst.reset();
    }

    /**
     * Copies up to max of the most recent client rotations (newest first) into the arrays.
     * @return number copied
     */
    public int recentRotations(float[] yawOut, float[] pitchOut, int max) {
        int n = Math.min(Math.min(max, rCount), Math.min(yawOut.length, pitchOut.length));
        for (int i = 0; i < n; i++) {
            int idx = (rHead - 1 - i + ROT) % ROT;
            yawOut[i] = rYaw[idx];
            pitchOut[i] = rPitch[idx];
        }
        return n;
    }

    /** Client yaw as of the given number of flying packets ago (0 = latest). */
    public float yawAgo(int ago) {
        if (rCount == 0) return yaw;
        int a = Math.min(ago, rCount - 1);
        return rYaw[(rHead - 1 - a + ROT) % ROT];
    }

    public float pitchAgo(int ago) {
        if (rCount == 0) return pitch;
        int a = Math.min(ago, rCount - 1);
        return rPitch[(rHead - 1 - a + ROT) % ROT];
    }

    public boolean recentlyTookVelocity(long now, long ms) { return now - lastVelocityMs < ms; }
}
