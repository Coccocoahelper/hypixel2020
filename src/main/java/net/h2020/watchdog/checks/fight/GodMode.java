package net.h2020.watchdog.checks.fight;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.TickTask;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;


/**
 * Fight.GodMode for 1.8: a player's hurt-time (noDamageTicks) only counts down while the server runs
 * EntityPlayer.l(), which happens once per received flying packet. A client that keeps talking (swings, attacks,
 * keep-alives) but withholds flying packets therefore stays invulnerable. NCP compares server ticks with the
 * noDamageTicks progress; we see the cause directly: hits arriving while the victim is invulnerable and has sent no
 * flying packet for a long time although other packets keep coming. Cancel = strip the invulnerability (as NCP).
 */
public final class GodMode extends Check {
    static final long NO_FLYING_MS = 450;

    public GodMode(WatchdogServiceImpl service) { super(service, CheckType.FIGHT_GODMODE); }

    /** Packet thread of the attacker, at its C02 ATTACK on targetId. Must not lock the victim's data. */
    public void onAttacked(int targetId, long now) {
        final Player victim = service.tracker().playerOf(targetId);
        if (victim == null || !victim.isOnline() || victim.isDead() || exempt(victim)) return;
        PlayerData vd = service.dataIfPresent(victim.getUniqueId());
        if (vd == null) return;
        long flyingAge = now - vd.lastFlyingMs;
        long packetAge = now - vd.lastPacketMs;
        if (flyingAge < NO_FLYING_MS || packetAge > 250 || TickTask.lagging()) return;
        if (now - vd.lastTeleportMs < 3000 || now - vd.joinMs < 5000) return; // terrain screen sends no flying packets
        int ndt;
        try { ndt = ((CraftPlayer) victim).getHandle().noDamageTicks; } catch (Throwable t) { return; }
        if (ndt <= 0) return;
        boolean cancel = flag(victim, Math.min(5.0, flyingAge / 400.0), "noflying=" + flyingAge + "ms ndt=" + ndt);
        if (cancel) service.runMain(() -> { if (victim.isOnline()) victim.setNoDamageTicks(0); });
    }
}
