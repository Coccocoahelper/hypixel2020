package net.h2020.watchdog.checks.fight;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Box;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.Geometry;
import net.h2020.watchdog.framework.Hitboxes;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * NCP Fight.Direction: the attacker's look ray must pass through the target hitbox. Tested against the last few
 * client rotations (the click happens between two movement packets), the possible eye positions and the
 * lag-compensated target boxes; the remaining miss distance (blocks) beyond the precision is the violation.
 */
public final class Direction extends Check {
    static final double PRECISION = 0.25;

    public Direction(WatchdogServiceImpl service) { super(service, CheckType.FIGHT_DIRECTION); }

    public boolean check(Player attacker, PlayerData d, Entity victim, long now) {
        if (exempt(attacker)) return false;
        List<Box> boxes = Hitboxes.targetBoxes(service, victim, attacker, now);
        List<double[]> eyes = Hitboxes.eyes(attacker, d);
        List<double[]> looks = Hitboxes.looks(attacker, d, 3);
        double min = Double.MAX_VALUE;
        outer:
        for (double[] e : eyes) {
            for (double[] l : looks) {
                for (Box b : boxes) {
                    double off = Geometry.rayOffset(e[0], e[1], e[2], l[0], l[1], l[2], b, 6.5);
                    if (off < min) min = off;
                    if (min <= 0) break outer;
                }
            }
        }
        if (min > PRECISION) {
            return flag(attacker, Math.min(min, 4.0) * 2.5, "off=" + Util.fmt(min));
        }
        reward(attacker, 0.97);
        if (debugging(attacker)) debug(attacker, "ok off=" + Util.fmt(min));
        return false;
    }
}
