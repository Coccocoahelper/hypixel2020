package net.h2020.watchdog.checks.inventory;

import net.h2020.watchdog.framework.ActionFrequency;

/** Per-player inventory state. Guarded by the owning PlayerData monitor. */
public final class InventoryData {
    // ---- open windows ----
    /** Own inventory open (1.8 clients send C16 OPEN_INVENTORY_ACHIEVEMENT when pressing E). */
    public boolean invOpen;
    /** A server window (chest, shop, ...) is open. */
    public boolean containerOpen;
    public int windowId;
    public long openMs;
    public int clicksSinceOpen;
    public int moveWhileOpenTicks;

    // ---- FastClick (NCP: 5 x 200ms buckets) ----
    public final ActionFrequency clickFreq = new ActionFrequency(5, 200);
    public long lastClickMs;
    public final double[] clickIntervals = new double[16];
    public int intervalHead, intervalCount;
    /** Click events still to be cancelled on the main thread. */
    public int pendingCancels;
    public long pendingCancelUntil;

    // ---- item use (bow / eating / drinking) ----
    public long bowStartMs, bowReleaseMs;
    public long useStartMs;

    public boolean anyOpen() { return invOpen || containerOpen; }

    public void closeAll() {
        invOpen = false;
        containerOpen = false;
        moveWhileOpenTicks = 0;
    }
}
