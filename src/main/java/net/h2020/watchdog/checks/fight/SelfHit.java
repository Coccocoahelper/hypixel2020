package net.h2020.watchdog.checks.fight;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import org.bukkit.entity.Player;

/** NCP Fight.SelfHit: attacking your own entity id (C02) or damaging yourself by melee. Always cancelled. */
public final class SelfHit extends Check {
    public SelfHit(WatchdogServiceImpl service) { super(service, CheckType.FIGHT_SELFHIT); }

    public boolean check(Player p, String how) {
        if (exempt(p)) return false;
        flag(p, 1.0, how);
        return true;
    }
}
