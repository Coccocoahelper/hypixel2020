package net.h2020.watchdog.checks.moving;

import net.h2020.watchdog.checks.net.NetData;
import org.bukkit.Location;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Per-player moving state (NCP MovingData). Fields marked "packet thread" are written from the player's packet
 * thread; everything else belongs to the main thread.
 */
final class MovingData {
    static final double HBUF_MAX = 0.15;
    static final double VBUF_MAX = 0.06;

    final UUID uuid;
    final String name;

    // ------------------------------------------------------------------ packet thread / shared
    final NetData net = new NetData();
    final ConcurrentLinkedQueue<FlyingEntry> queue = new ConcurrentLinkedQueue<>();
    final AtomicInteger queueSize = new AtomicInteger();
    final TeleportTracker teleports = new TeleportTracker();
    final VelocityTracker velocity = new VelocityTracker();
    final GhostBlocks ghosts = new GhostBlocks();
    /** Number of flying packets received. */
    volatile long inSeq;
    /** Wall time of the last C13 claiming isFlying. */
    volatile long lastClientFlyClaim;
    /** Ping snapshot for packet-thread computations. */
    volatile int pingSnapshot;
    /** Flying sequence numbers of the last client ticks in which the player attacked (C02 ATTACK). */
    final AtomicLongArray attackSeqs = new AtomicLongArray(4);
    final AtomicInteger attackIdx = new AtomicInteger();
    // MorePackets (NCP moving.morepackets buffer), packet thread
    double mpBuffer = MorePackets.BUFFER_START;
    double mpLast = -1;
    double mpLastPacket = -1;
    double mpLastFlag = -1e12;

    // ------------------------------------------------------------------ main thread
    /** inSeq snapshot taken by the previous tick task: entries up to it were processed by the server. */
    long prevTickSeq, prevTickSeq2;
    boolean hasPos;
    String world;
    double x, y, z;
    /** Waiting for a teleport confirmation; entries are skipped until one arrives. */
    boolean awaitTeleport;
    long awaitSince;
    /** Last legit position (ground / liquid / climbable) - NCP sfSetBack. */
    Location setBack;
    /** Set-back we initiated (keeps NoFall distance through it). */
    Location expectedSetback;
    /** A set-back was requested from a packet-thread check. */
    volatile boolean setbackRequested;

    // SurvivalFly physics chain (upper bounds of the motion carried into the next tick)
    double lastHDist, lastDy;
    double nextHBase;
    double nextVHigh, nextVLow;
    /** The previous tick(s) sent no position (moved < 0.03 since the last report). */
    boolean unreported;
    /** onGround of the previous flying packet (the client's ground state at the start of the next tick). */
    boolean prevClientGround;
    double hBuffer = HBUF_MAX;
    double vBuffer = VBUF_MAX;
    int leniencyTicks;
    /** Air ticks left in which the legacy 2020 bunnyhop allowance still applies (see SurvivalFly). */
    int bunnyTicks;
    boolean sprintPrev;
    int sneakTicks, blockTicks;
    PosInfo lastInfo;
    long lastInfoTick = -1;

    // Grace windows (client applies attribute/potion/ability changes one latency later)
    int speedAmp = -1, jumpAmp = -1;
    long speedUntil, jumpUntil;
    double walkBase = 0.1;
    long walkUntil;
    long flyGraceUntil;

    // Entity push allowance cache
    long pushTick = -1;
    double pushValue;

    // NoFall
    double noFallDist;
    int spoofTicks;
    boolean spoofFlagged;
    double noFallPending;
    int noFallPendingTicks;
    boolean noFallPendingSpoofed;
    /** The previous tick was a ground claim accepted by the legacy 2020 NoFall gap (see NoFall.legacyReset). */
    boolean noFallLegacyPrev;

    // Hover
    int hoverTicks;
    double hoverX, hoverY, hoverZ;
    long hoverSeq;

    // Velocity (anti-knockback)
    VelocityTracker.Entry kbEntry;
    int kbLastId;
    long kbWindowEnd;
    double kbBestErr;
    double kbBestRatio;
    double kbAccel;
    boolean kbTookV, kbInvalidH, kbInvalidV, kbSawMove;
    final boolean[] kbHistory = new boolean[8];
    int kbHistoryIdx, kbHistoryCount;

    boolean debug;

    MovingData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    /** Reset the physics chain at a known position with zero motion (teleport confirmed, respawn, resync). */
    void resetPhysics(String world, double x, double y, double z) {
        this.world = world;
        this.x = x; this.y = y; this.z = z;
        this.hasPos = true;
        this.lastHDist = 0;
        this.lastDy = 0;
        this.bunnyTicks = 0;
        this.nextHBase = 0;
        this.nextVHigh = 0;
        this.nextVLow = 0;
        this.unreported = false;
        this.lastInfo = null;
        this.hoverTicks = 0;
        this.kbEntry = null;
    }

    void resetNoFall() {
        noFallDist = 0;
        spoofTicks = 0;
        spoofFlagged = false;
        noFallPending = 0;
        noFallPendingTicks = 0;
        noFallLegacyPrev = false;
    }

    /** Packet thread: an attack belongs to the client tick whose flying packet comes next. */
    void recordAttack() {
        int i = attackIdx.getAndIncrement() & 3;
        attackSeqs.set(i, inSeq + 1);
    }

    boolean attackedIn(long seq) {
        for (int i = 0; i < 4; i++) if (attackSeqs.get(i) == seq) return true;
        return false;
    }

    void clearQueue() {
        queue.clear();
        queueSize.set(0);
    }
}
