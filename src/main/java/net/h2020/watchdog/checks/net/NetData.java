package net.h2020.watchdog.checks.net;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;

/**
 * Per-player state of the net checks. Written from the player's packet thread (one Netty event loop for a
 * human, the main thread for a bot); keep-alive state may also be touched from the outbound thread, so that part
 * is guarded by synchronizing on this object.
 */
public final class NetData {
    /** null until the first packet; true when the player's packets arrive on the main thread (bot). */
    Boolean mainThreadClock;

    // --- Timer (balance of client ticks vs elapsed time) ---
    double timerLast = -1;
    double timerBalance;
    double timerMaxGap;
    double timerMaxGapAt;
    int timerPackets;

    // --- FlyingFrequency (packets per second over 5 one-second buckets) ---
    final long[] freqBucketId = new long[FlyingFrequency.BUCKETS];
    final int[] freqCount = new int[FlyingFrequency.BUCKETS];
    double freqLastPacket = -1;
    double freqLastFlag = -1e12;

    // --- KeepAlive ---
    /** {id, sentWallMs, flyingCountAtSend, flaggedDelay} */
    final ArrayDeque<long[]> kaPending = new ArrayDeque<>();
    final LinkedHashSet<Integer> kaHistory = new LinkedHashSet<>();
    boolean kaAnswered;
    long flyingCount;

    // --- PacketOrder ---
    int sprintToggles;
    int sneakToggles;
    boolean blocking;
    boolean releasedThisTick;
    final ArrayDeque<Double> orderSpam = new ArrayDeque<>();
    final ArrayDeque<Double> orderViolations = new ArrayDeque<>();
    /** Set from the main thread (respawn/world change) to clear client-state tracking on the next packet. */
    public volatile boolean resetRequested;

    void resetTiming() {
        timerLast = -1;
        timerBalance = 0;
        timerMaxGap = 0;
        timerMaxGapAt = 0;
        timerPackets = 0;
        for (int i = 0; i < freqCount.length; i++) { freqCount[i] = 0; freqBucketId[i] = 0; }
        freqLastPacket = -1;
        orderSpam.clear();
        orderViolations.clear();
    }

    void resetClientState() {
        sprintToggles = 0;
        sneakToggles = 0;
        blocking = false;
        releasedThisTick = false;
    }
}
