package net.h2020.watchdog.checks.fight;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * NCP Fight.Critical at packet level: the server grants a critical when fallDistance > 0 and !onGround. A real
 * critical needs a jump (or a fall below the last ground). "Packet criticals" fake it with tiny y offsets
 * (0.0625, 0.11, 1.1E-5 ...) above a ground they never left: the rise above the last ground y stays tiny and the
 * client is still at/above ground level.
 * The movement pattern is sampled when the C02 arrives (exact packet order) and judged in the damage event, where
 * the server's crit decision is known.
 */
public final class Critical extends Check {
    static final double MIN_ASCENT = 0.13;

    public Critical(WatchdogServiceImpl service) { super(service, CheckType.FIGHT_CRITICAL); }

    /** Packet thread, at C02 ATTACK. */
    public void sample(PlayerData d, int target, long now) {
        FightData fd = d.fight;
        double ascent = d.maxYSinceGround - d.lastGroundY;
        double aboveGround = d.y - d.lastGroundY;
        fd.critTarget = target;
        fd.critAscent = ascent;
        fd.critAir = d.airTicks;
        fd.critSuspect = d.hasPosition && !d.onGround && ascent < MIN_ASCENT && aboveGround > -0.005 && d.airTicks <= 12
                && now - d.lastVelocityMs > 1200 && now - d.lastTeleportMs > 1500;
    }

    /** Main thread, damage event. @return cancel */
    public boolean check(Player attacker, PlayerData d, int victimId) {
        if (exempt(attacker)) return false;
        FightData fd = d.fight;
        if (fd.critTarget != victimId) return false;
        fd.critTarget = -1;
        EntityPlayer h = ((CraftPlayer) attacker).getHandle();
        if (!(h.fallDistance > 0f && !h.onGround)) return false; // the server grants no critical anyway
        if (attacker.isFlying() || attacker.isInsideVehicle() || attacker.hasPotionEffect(PotionEffectType.BLINDNESS)) return false;
        if (Util.inSpecialMedium(attacker)) return false;
        String det = "ascent=" + String.format(java.util.Locale.ROOT, "%.4f", fd.critAscent) + " air=" + fd.critAir
                + " fd=" + Util.fmt(h.fallDistance);
        if (fd.critSuspect) {
            fd.criticalBuffer += 1;
            if (fd.criticalBuffer >= 2) return flag(attacker, 2.0, det);
            debug(attacker, det + " (buffered)");
            return false;
        }
        fd.criticalBuffer = Math.max(0, fd.criticalBuffer - 0.5);
        if (debugging(attacker)) debug(attacker, "ok crit " + det);
        return false;
    }
}
