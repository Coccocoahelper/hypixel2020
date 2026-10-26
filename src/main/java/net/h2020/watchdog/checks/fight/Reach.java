package net.h2020.watchdog.checks.fight;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Box;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.Geometry;
import net.h2020.watchdog.framework.Hitboxes;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * NCP Fight.Reach adapted to 1.8 hit geometry: shortest distance from any plausible attacker eye position to any
 * target hitbox (+0.1 border) the attacker could have seen within its ping. Vanilla survival reach is 3.0 to the
 * bordered box, creative 6.0 for entities. Violations carry a 500ms penalty during which further hits are cancelled.
 */
public final class Reach extends Check {
    static final double SURVIVAL = 3.0, CREATIVE = 6.0;
    /** Measurement slack on top of the vanilla limit. */
    static final double TOLERANCE = 0.06;
    static final long PENALTY_MS = 500;

    public Reach(WatchdogServiceImpl service) { super(service, CheckType.FIGHT_REACH); }

    /** Main thread, d locked. @return cancel */
    public boolean check(Player attacker, PlayerData d, Entity victim, long now) {
        if (exempt(attacker)) return false;
        FightData fd = d.fight;
        if (now < fd.reachPenaltyUntil) {
            debug(attacker, "penalty active, hit cancelled");
            return true;
        }
        double limit = attacker.getGameMode() == GameMode.CREATIVE ? CREATIVE : SURVIVAL;
        double adminLimit = service.config().adminReachLimit;
        if (adminLimit > 0 && !Util.isBot(attacker) && Util.isAdmin(attacker)) limit = Math.max(limit, adminLimit);
        List<Box> boxes = Hitboxes.targetBoxes(service, victim, attacker, now);
        List<double[]> eyes = Hitboxes.eyes(attacker, d);
        double min = Double.MAX_VALUE;
        for (double[] e : eyes) for (Box b : boxes) min = Math.min(min, Geometry.distanceToBox(e[0], e[1], e[2], b));
        double violation = min - limit;
        int ping = Util.ping(attacker);
        if (violation > TOLERANCE) {
            fd.reachBuffer += 1.0;
            if (fd.reachBuffer >= 2.0 || violation > 0.35) {
                boolean cancel = flag(attacker, violation * 10.0, "reach=" + Util.fmt(min) + " ping=" + ping);
                if (cancel) fd.reachPenaltyUntil = now + PENALTY_MS;
                return cancel;
            }
            debug(attacker, "reach=" + Util.fmt(min) + " (buffered " + Util.fmt1(fd.reachBuffer) + ")");
            return false;
        }
        fd.reachBuffer = Math.max(0, fd.reachBuffer - 0.25);
        reward(attacker, 0.95);
        if (debugging(attacker)) debug(attacker, "ok reach=" + Util.fmt(min) + " samples=" + boxes.size() + " ping=" + ping);
        return false;
    }
}
