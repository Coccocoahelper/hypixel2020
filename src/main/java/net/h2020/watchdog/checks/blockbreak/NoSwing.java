package net.h2020.watchdog.checks.blockbreak;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import org.bukkit.entity.Player;

/**
 * NCP BlockBreak.NoSwing: the 1.8 client swings when it starts digging and every tick while digging. A block broken
 * with no swing since its START is a violation. For instant breaks (server breaks on START) the swing of the same
 * client tick may arrive after the event, so those are judged at the end of the tick.
 */
public final class NoSwing extends Check {
    public NoSwing(WatchdogServiceImpl service) { super(service, CheckType.BLOCKBREAK_NOSWING); }

    /** Main thread. @return cancel */
    public boolean onBreak(Player p, PlayerData d, BlockBreakData.Dig dig, boolean instant) {
        if (dig.swings > 0) return false;
        BlockBreakData bd = d.dig;
        if (instant && dig.startTick == d.flyingCount) {
            bd.noSwingPending = true;
            bd.noSwingPendingTick = dig.startTick;
            return false;
        }
        return !exempt(p) && flag(p, 1.0, "broke without swing" + (instant ? " (instant)" : ""));
    }

    /** Packet thread: any swing. */
    public void onSwing(PlayerData d) {
        BlockBreakData bd = d.dig;
        if (bd.noSwingPending && bd.noSwingPendingTick == d.flyingCount) bd.noSwingPending = false;
    }

    /** Packet thread: end of client tick. */
    public void onTickEnd(Player p, PlayerData d) {
        BlockBreakData bd = d.dig;
        if (!bd.noSwingPending || bd.noSwingPendingTick >= d.flyingCount) return;
        bd.noSwingPending = false;
        if (!exempt(p)) flag(p, 1.0, "broke without swing (instant)");
    }
}
