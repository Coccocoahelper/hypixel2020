package net.h2020.watchdog.checks.blockplace;

import net.h2020.watchdog.framework.ActionFrequency;

/** Per-player block place state. Guarded by the owning PlayerData monitor. */
public final class BlockPlaceData {
    // ---- FastPlace (NCP: full window + short-term ticks) ----
    public final ActionFrequency fastPlaceBuckets = new ActionFrequency(2, 500);
    public long shortTermStart = Long.MIN_VALUE / 2;
    public int shortTermCount;
    /** Block place events until this time are cancelled (set by FastPlace on the packet thread). */
    public long cancelPlaceUntil;

    // ---- last C08 that targeted a block face while holding a block ----
    public int lastX, lastY, lastZ, lastFace = -1;
    public long lastPacketMs;
    /** Client tick (flying count) of that C08; whether its swing arrived; whether the server placed it. */
    public long placeTick = Long.MIN_VALUE / 2;
    public boolean placeSwung, placeConfirmed;

    // ---- Scaffold ----
    public boolean snapPending;
    public float snapYawAtPlace, snapYawBefore;
    public int snapTicksLeft;
    public final long[] snapTimes = new long[4];
    public int snapIdx;
    public final long[] sprintPlaceTimes = new long[8];
    public int sprintIdx;
}
