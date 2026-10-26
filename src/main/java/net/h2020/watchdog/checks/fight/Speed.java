package net.h2020.watchdog.checks.fight;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.entity.Player;

/**
 * NCP Fight.Speed: attacks per second. Long term: 6 buckets of 333ms (2s) against the limit; short term: attacks
 * within 10 client ticks against ceil(limit * 10 / 20) + 2. Counted on raw C02 ATTACK packets, so attacks absorbed by
 * hurt-time count too. Cancel drops the attack packet.
 */
public final class Speed extends Check {
    /** NCP ships 15; raised to 20 so legit jitter/butterfly clickers (as tolerated on Hypixel) never trip it. */
    static final int LIMIT = 20;
    static final int SHORT_TERM_TICKS = 10;

    public Speed(WatchdogServiceImpl service) { super(service, CheckType.FIGHT_SPEED); }

    /** Packet thread. @return cancel */
    public boolean check(Player p, PlayerData d, long now) {
        if (exempt(p)) return false;
        FightData fd = d.fight;
        fd.speedBuckets.add(now, 1f);
        float fullTime = fd.speedBuckets.span() / 1000f;
        float fullRate = fd.speedBuckets.score(1f) / fullTime;

        if (d.flyingCount - fd.speedShortTermStart < SHORT_TERM_TICKS) fd.speedShortTermCount++;
        else { fd.speedShortTermStart = d.flyingCount; fd.speedShortTermCount = 1; }
        int shortLimit = (int) Math.ceil(LIMIT * SHORT_TERM_TICKS / 20.0) + 2; // bursts of double clicks

        double violation = Math.max(fullRate - LIMIT, fd.speedShortTermCount - shortLimit);
        if (violation > 0) {
            return flag(p, violation, "aps=" + Util.fmt1(fullRate) + " short=" + fd.speedShortTermCount + "/" + SHORT_TERM_TICKS + "t");
        }
        return false;
    }
}
