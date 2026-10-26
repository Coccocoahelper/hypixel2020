package net.h2020.watchdog.checks.fight;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.Geometry;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Iterator;

/**
 * NCP Fight.Angle (multi-aura heuristic): over the hits of the last second, combines how little the attacker moved,
 * how quickly hits followed each other, how far the yaw jumped between hits and how often the target switched.
 * Damage events are throttled by hurt time per target, so fast hits with big yaw jumps mean several targets.
 */
public final class Angle extends Check {
    static final double THRESHOLD = 55.0;

    public Angle(WatchdogServiceImpl service) { super(service, CheckType.FIGHT_ANGLE); }

    public boolean check(Player attacker, PlayerData d, Entity victim, long now) {
        if (exempt(attacker)) return false;
        FightData fd = d.fight;
        Location l = attacker.getLocation();
        fd.angleHits.addLast(new FightData.AngleHit(l.getX(), l.getY(), l.getZ(), l.getYaw(), now, victim.getEntityId()));
        while (!fd.angleHits.isEmpty() && (now - fd.angleHits.peekFirst().time > 1000 || fd.angleHits.size() > 10)) {
            fd.angleHits.removeFirst();
        }
        if (fd.angleHits.size() < 2) return false;

        double deltaMove = 0, deltaTime = 0, deltaYaw = 0, deltaSwitch = 0;
        Iterator<FightData.AngleHit> it = fd.angleHits.iterator();
        FightData.AngleHit prev = it.next();
        while (it.hasNext()) {
            FightData.AngleHit cur = it.next();
            double dx = cur.x - prev.x, dy = cur.y - prev.y, dz = cur.z - prev.z;
            deltaMove += Math.sqrt(dx * dx + dy * dy + dz * dz);
            deltaTime += cur.time - prev.time;
            deltaYaw += Geometry.yawDiff(cur.yaw, prev.yaw);
            if (cur.target != prev.target) deltaSwitch += 1;
            prev = cur;
        }
        int n = fd.angleHits.size() - 1;
        double avgMove = deltaMove / n, avgTime = deltaTime / n, avgYaw = deltaYaw / n, avgSwitch = deltaSwitch / n;

        double violation = 0;
        if (avgMove >= 0 && avgMove < 0.2) violation += 20.0 * (0.2 - avgMove) / 0.2;
        if (avgTime >= 0 && avgTime < 150) violation += 30.0 * (150 - avgTime) / 150.0;
        if (avgYaw > 50) violation += 30.0 * avgYaw / 180.0;
        if (avgSwitch > 0) violation += 20.0 * avgSwitch;

        String det = "score=" + Util.fmt1(violation) + " move=" + Util.fmt(avgMove) + " time=" + (int) avgTime
                + "ms yaw=" + Util.fmt1(avgYaw) + " switch=" + Util.fmt(avgSwitch);
        if (violation > THRESHOLD) return flag(attacker, violation, det);
        reward(attacker, 0.98);
        if (debugging(attacker)) debug(attacker, "ok " + det);
        return false;
    }
}
