package net.h2020.watchdog.checks.blockbreak;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.BlockGeometry;
import net.h2020.watchdog.framework.Box;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * NCP BlockBreak.Direction: the crosshair must be on the block when it breaks (the 1.8 client restarts digging as
 * soon as the crosshair leaves the block). Nukers / bed breakers through walls fail.
 */
public final class Direction extends Check {
    static final double PRECISION = 0.25;

    public Direction(WatchdogServiceImpl service) { super(service, CheckType.BLOCKBREAK_DIRECTION); }

    public boolean check(Player p, PlayerData d, Block b) {
        if (exempt(p)) return false;
        double off = BlockGeometry.directionOffset(p, d, Box.block(b.getX(), b.getY(), b.getZ()));
        if (off > PRECISION) return flag(p, Math.min(off, 4.0) * 2.0, "off=" + Util.fmt(off));
        reward(p, 0.97);
        return false;
    }
}
