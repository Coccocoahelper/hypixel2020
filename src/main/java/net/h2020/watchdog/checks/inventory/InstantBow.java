package net.h2020.watchdog.checks.inventory;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import org.bukkit.entity.Player;

/**
 * NCP Inventory.InstantBow: real time between the draw (C08 use with a bow) and the release (C07 RELEASE_USE_ITEM)
 * against the time vanilla needs for the reported force. Server force = (f^2 + 2f) / 3 with f = ticks / 20, and
 * ticks advance per flying packet, so packet-spam "fastbow" shoots full force after a few milliseconds.
 */
public final class InstantBow extends Check {
    static final long DELAY = 130;

    public InstantBow(WatchdogServiceImpl service) { super(service, CheckType.INVENTORY_INSTANTBOW); }

    /** Main thread, EntityShootBowEvent. @return cancel */
    public boolean check(Player p, PlayerData d, float force, long now) {
        InventoryData id = d.inv;
        long start = id.bowStartMs;
        id.bowStartMs = 0;
        if (start == 0 || exempt(p)) return false;
        long release = id.bowReleaseMs >= start ? id.bowReleaseMs : now;
        long pull = release - start;
        double f = Math.sqrt(1.0 + 3.0 * Math.min(1.0, Math.max(0.0, force))) - 1.0; // seconds of drawing
        long expected = (long) (f * 1000.0);
        long allowed = (long) (expected * 0.9) - DELAY;
        if (pull < allowed) {
            return flag(p, Math.max(1.0, (allowed - pull) / 100.0), "pull=" + pull + "ms force="
                    + String.format(java.util.Locale.ROOT, "%.2f", force) + " expected=" + expected + "ms");
        }
        if (debugging(p)) debug(p, "ok pull=" + pull + "ms expected=" + expected + "ms");
        return false;
    }
}
