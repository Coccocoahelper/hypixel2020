package net.h2020.watchdog.checks.fight;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Box;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.Geometry;
import net.h2020.watchdog.framework.Hitboxes;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Watchdog-style aim heuristics (deliberately conservative):
 * <ol>
 * <li><b>GCD</b>: vanilla turns the mouse in whole mouse counts, so pitch deltas are multiples of
 *   (sens*0.6+0.2)^3 * 8 * 0.15 degrees (>= 0.0096deg even at 0% sensitivity). Euclid on fixed-point deltas recovers
 *   that step for humans; rotations set by an aura/aimbot have no common step and collapse to tiny GCDs. Only
 *   sampled in combat, outside pitch clamps and teleports; flags when the large majority of 60 samples fail.</li>
 * <li><b>Centre lock</b>: across 25 hits the crosshair sits on the target's exact centre/eye point with almost no
 *   spread. Human (and human-model bot) aim error is degrees, with variance.</li>
 * </ol>
 */
public final class Aim extends Check {
    static final int GCD_SAMPLES = 60;
    static final long MIN_GCD = 131072L; // 2^17 fixed-point = 0.0078 degrees
    static final int CENTER_SAMPLES = 25;

    public Aim(WatchdogServiceImpl service) { super(service, CheckType.FIGHT_AIM); }

    /** Packet thread, after rotation update. */
    public void onRotation(Player p, PlayerData d, boolean hasLook, long now) {
        if (!hasLook || d.skipRotationSamples > 0 || now - d.lastAttackMs > 3000) return;
        FightData fd = d.fight;
        float dp = Math.abs(d.pitch - d.prevPitch);
        if (dp < 0.05f || dp > 10f || Math.abs(d.pitch) > 85f || Math.abs(d.prevPitch) > 85f) return;
        long cur = (long) (dp * Geometry.GCD_EXPANDER);
        long prev = fd.gcdPrev;
        fd.gcdPrev = cur;
        if (prev <= 0) return;
        long g = Geometry.gcd(cur, prev);
        fd.gcdSamples++;
        if (g < MIN_GCD) fd.gcdBad++;
        if (fd.gcdSamples < GCD_SAMPLES) return;
        double ratio = fd.gcdBad / (double) fd.gcdSamples;
        fd.gcdSamples = 0;
        fd.gcdBad = 0;
        if (exempt(p)) return;
        if (ratio > 0.8) flag(p, (ratio - 0.6) * 25.0, "gcd ratio=" + Util.fmt(ratio));
        else if (debugging(p)) debug(p, "ok gcd ratio=" + Util.fmt(ratio));
    }

    /** Main thread, damage event: precision of the crosshair relative to the target centre. */
    public void onAttack(Player attacker, PlayerData d, Entity victim, long now) {
        if (exempt(attacker) || !d.hasRotation) return;
        Location l = attacker.getLocation();
        double ex = l.getX(), ey = l.getY() + Util.eyeHeight(attacker.isSneaking()), ez = l.getZ();
        double[] look = Geometry.direction(d.yaw, d.pitch);
        List<Box> boxes = Hitboxes.targetBoxes(service, victim, attacker, now);
        double err = Double.MAX_VALUE;
        for (Box b : boxes) {
            double cx = b.centerX(), cz = b.centerZ();
            double dx = cx - ex, dz = cz - ez;
            if (dx * dx + dz * dz < 1.44) return; // too close: angles are meaningless
            err = Math.min(err, Geometry.angleTo(ex, ey, ez, look, cx, b.centerY(), cz));
            err = Math.min(err, Geometry.angleTo(ex, ey, ez, look, cx, b.minY + Hitboxes.BORDER + 1.62, cz));
        }
        FightData fd = d.fight;
        fd.aimErr[fd.aimErrCount++] = err;
        if (fd.aimErrCount < CENTER_SAMPLES) return;
        double mean = Geometry.mean(fd.aimErr, CENTER_SAMPLES), sd = Geometry.stdDev(fd.aimErr, CENTER_SAMPLES);
        fd.aimErrCount = 0;
        String det = "centre mean=" + Util.fmt(mean) + " sd=" + Util.fmt(sd);
        if (mean < 0.5 && sd < 0.25) flag(attacker, 6.0, det);
        else if (debugging(attacker)) debug(attacker, "ok " + det);
    }
}
