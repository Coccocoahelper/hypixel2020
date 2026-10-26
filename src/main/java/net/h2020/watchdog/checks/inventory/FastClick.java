package net.h2020.watchdog.checks.inventory;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.Geometry;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.entity.Player;

/**
 * NCP Inventory.FastClick on C0E window clicks (drag and double-click follow-up packets excluded):
 * short term (> 5 clicks per 200ms), normal (> 18 per second), the first click in a freshly opened container
 * faster than humanly possible (< 70ms, chest stealers), and machine-regular intervals (stdDev < 3ms over 15 clicks
 * at 12+ clicks/s). Cancelled clicks are rejected in InventoryClickEvent and the inventory is resynced.
 */
public final class FastClick extends Check {
    static final float SHORT_TERM_LIMIT = 5f;
    static final float NORMAL_LIMIT = 18f;
    static final long CHEST_OPEN_DELAY = 70;

    public FastClick(WatchdogServiceImpl service) { super(service, CheckType.INVENTORY_FASTCLICK); }

    /** Packet thread. */
    public void onClick(Player p, PlayerData d, int windowId, int mode, long now) {
        InventoryData id = d.inv;
        if (mode == 5 || mode == 6) return; // drag painting / double-click collect send bursts by design
        boolean firstInContainer = windowId != 0 && id.containerOpen && id.clicksSinceOpen == 0;
        id.clicksSinceOpen++;
        if (id.lastClickMs != 0 && now - id.lastClickMs < 1000) {
            id.clickIntervals[id.intervalHead] = now - id.lastClickMs;
            id.intervalHead = (id.intervalHead + 1) % id.clickIntervals.length;
            if (id.intervalCount < id.clickIntervals.length) id.intervalCount++;
        } else {
            id.intervalCount = 0;
        }
        id.lastClickMs = now;
        if (exempt(p)) return;

        id.clickFreq.add(now, 1f);
        float shortTerm = id.clickFreq.bucketScore(0);
        float normal = id.clickFreq.score(1f);
        double violation = Math.max(shortTerm - SHORT_TERM_LIMIT, normal - NORMAL_LIMIT);
        boolean cancel = false;
        if (violation > 0) cancel = flag(p, violation, "short=" + (int) shortTerm + " cps=" + (int) normal);

        if (firstInContainer && now - id.openMs < CHEST_OPEN_DELAY) {
            if (flag(p, 2.0, "first click " + (now - id.openMs) + "ms after open")) cancel = true;
        }

        if (id.intervalCount >= 15) {
            double mean = Geometry.mean(id.clickIntervals, id.intervalCount);
            double sd = Geometry.stdDev(id.clickIntervals, id.intervalCount);
            if (mean < 84 && sd < 3) {
                id.intervalCount = 0;
                if (flag(p, 3.0, "regular interval=" + Util.fmt1(mean) + "ms sd=" + Util.fmt1(sd))) cancel = true;
            }
        }
        if (cancel) {
            id.pendingCancels++;
            id.pendingCancelUntil = now + 250;
        }
    }
}
