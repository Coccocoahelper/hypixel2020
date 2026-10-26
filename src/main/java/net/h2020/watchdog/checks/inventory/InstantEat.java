package net.h2020.watchdog.checks.inventory;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import org.bukkit.entity.Player;

/**
 * NCP Inventory.InstantEat: an item consumed (practically) immediately after the use started. Vanilla eating and
 * drinking take 32 player ticks (1.6s); below 300ms of real time is an instant-eat hack.
 */
public final class InstantEat extends Check {
    static final long INSTANT_MS = 300;

    public InstantEat(WatchdogServiceImpl service) { super(service, CheckType.INVENTORY_INSTANTEAT); }

    /** @return cancel; elapsed in ms since the use started */
    public boolean check(Player p, long elapsed, String item) {
        if (exempt(p) || elapsed >= INSTANT_MS) return false;
        return flag(p, Math.max(1.0, (1600 - elapsed) / 300.0), "eat=" + elapsed + "ms " + item);
    }
}
