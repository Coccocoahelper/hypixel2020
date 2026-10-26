package net.h2020.watchdog.checks.fight;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import org.bukkit.entity.Player;

/**
 * NCP Fight.NoSwing: every 1.8 left click sends C0A (arm animation) before C02 (attack) in the same client tick.
 * Attacks are matched to swings of their tick; an attack still unmatched when the tick's flying packet arrives is a
 * violation (tolerates either packet order). Cancelling blocks attacks for the next second.
 */
public final class NoSwing extends Check {
    public NoSwing(WatchdogServiceImpl service) { super(service, CheckType.FIGHT_NOSWING); }

    public void onAttack(PlayerData d) {
        FightData fd = d.fight;
        if (d.swingsThisTick > fd.swingsUsed) fd.swingsUsed++;
        else fd.noSwingPending++;
    }

    public void onSwing(PlayerData d) {
        FightData fd = d.fight;
        if (fd.noSwingPending > 0) {
            fd.noSwingPending--;
            fd.swingsUsed++;
        }
    }

    /** End of the client tick. */
    public void onTickEnd(Player p, PlayerData d, long now) {
        FightData fd = d.fight;
        int missing = fd.noSwingPending;
        fd.noSwingPending = 0;
        fd.swingsUsed = 0;
        if (missing <= 0 || exempt(p)) return;
        if (flag(p, missing, "attacks without swing=" + missing)) fd.noSwingCancelUntil = now + 1000;
    }
}
