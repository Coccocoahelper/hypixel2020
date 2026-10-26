package net.h2020.watchdog.checks.fight;

import net.h2020.watchdog.framework.ActionFrequency;

import java.util.ArrayDeque;

/** Per-player fight check state. Guarded by the owning PlayerData monitor. */
public final class FightData {
    // ---- Speed (NCP: 6 buckets x 333ms + short-term ticks) ----
    public final ActionFrequency speedBuckets = new ActionFrequency(6, 333);
    public long speedShortTermStart = Long.MIN_VALUE / 2;
    public int speedShortTermCount;

    // ---- NoSwing ----
    /** Swings of the current client tick already matched to an attack. */
    public int swingsUsed;
    /** Attacks of the current client tick without a matching swing (yet). */
    public int noSwingPending;
    public long noSwingCancelUntil;

    // ---- Reach ----
    public double reachBuffer;
    public long reachPenaltyUntil;

    // ---- Angle ----
    public static final class AngleHit {
        final double x, y, z;
        final float yaw;
        final long time;
        final int target;

        AngleHit(double x, double y, double z, float yaw, long time, int target) {
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.time = time; this.target = target;
        }
    }
    public final ArrayDeque<AngleHit> angleHits = new ArrayDeque<AngleHit>();

    // ---- Critical ----
    public double criticalBuffer;
    public int critTarget = -1, critAir;
    public double critAscent;
    public boolean critSuspect;
    /** The sampled attack followed a 2020 packet-crit burst accepted by legacy-2020-crits-gap. */
    public boolean critLegacy;
    /** Entity id of the most recent C02 attack target. */
    public int lastTarget = -1;

    // ---- FastHeal ----
    public long healLast;
    public double healBuffer = FastHeal.BUFFER;

    // ---- Aim: rotation GCD ----
    public long gcdPrev;
    public int gcdSamples, gcdBad;
    // ---- Aim: target-centre precision ----
    public final double[] aimErr = new double[Aim.CENTER_SAMPLES];
    public int aimErrCount;

    // ---- ClickPattern ----
    public static final int CLICKS = 40;
    public final long[] clickMs = new long[CLICKS];
    public final long[] clickTick = new long[CLICKS];
    public int clickHead, clickCount, clicksSinceAnalysis;
    public long cpsWindowStart;
    public int cpsWindowCount, cpsStreak;
    /** Client ticks (within the analysed clicks) that carried 3+ swings. */
    public int multiClickTicks;
}
