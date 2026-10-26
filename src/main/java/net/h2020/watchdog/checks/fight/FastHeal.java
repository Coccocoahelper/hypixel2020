package net.h2020.watchdog.checks.fight;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.TickTask;
import org.bukkit.entity.Player;

/**
 * NCP Fight.FastHeal: 1.8 food regeneration heals once per 80 player ticks (4000ms). Player ticks are driven by
 * flying packets, so timer/regen hacks heal faster. Shortfalls are paid from a 1000ms buffer that refills with
 * surplus time; once it is empty the regain is a violation.
 */
public final class FastHeal extends Check {
    static final long INTERVAL = 4000;
    static final double BUFFER = 1000;

    public FastHeal(WatchdogServiceImpl service) { super(service, CheckType.FIGHT_FASTHEAL); }

    /** Main thread, SATIATED regain. @return cancel */
    public boolean check(Player p, PlayerData d, long now) {
        if (exempt(p)) return false;
        FightData fd = d.fight;
        long last = fd.healLast;
        fd.healLast = now;
        if (last == 0) return false;
        long delta = now - last;
        if (delta >= INTERVAL) {
            fd.healBuffer = Math.min(BUFFER, fd.healBuffer + (delta - INTERVAL));
            return false;
        }
        if (TickTask.lagging()) return false; // burst after a lag spike
        fd.healBuffer -= (INTERVAL - delta);
        if (fd.healBuffer < 0) {
            double vl = -fd.healBuffer / 1000.0;
            fd.healBuffer = 0;
            return flag(p, Math.max(0.5, vl), "interval=" + delta + "ms");
        }
        debug(p, "interval=" + delta + "ms buffer=" + (int) fd.healBuffer);
        return false;
    }
}
