package net.h2020.watchdog.checks.inventory;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import org.bukkit.entity.Player;

/**
 * NCP Inventory.FastConsume: eating/drinking faster than vanilla (32 ticks = 1600ms) but not instantly - "fast eat"
 * via timer/flying packet spam. Lenient threshold of 1300ms absorbs network jitter.
 */
public final class FastConsume extends Check {
    static final long MIN_MS = 1300;

    public FastConsume(WatchdogServiceImpl service) { super(service, CheckType.INVENTORY_FASTCONSUME); }

    /** @return cancel */
    public boolean check(Player p, long elapsed, String item) {
        if (exempt(p) || elapsed < InstantEat.INSTANT_MS || elapsed >= MIN_MS) return false;
        return flag(p, Math.max(1.0, (MIN_MS - elapsed) / 100.0), "consume=" + elapsed + "ms " + item);
    }
}
