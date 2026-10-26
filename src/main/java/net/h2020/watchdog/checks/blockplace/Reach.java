package net.h2020.watchdog.checks.blockplace;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.BlockGeometry;
import net.h2020.watchdog.framework.Box;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** NCP BlockPlace.Reach: eye to the clicked block (vanilla client: 4.5 survival, 5.0 creative). Limits 5.0 / 6.0. */
public final class Reach extends Check {
    static final double SURVIVAL = 5.0, CREATIVE = 6.0;

    public Reach(WatchdogServiceImpl service) { super(service, CheckType.BLOCKPLACE_REACH); }

    public boolean check(Player p, PlayerData d, Block clicked) {
        if (exempt(p)) return false;
        double limit = p.getGameMode() == GameMode.CREATIVE ? CREATIVE : SURVIVAL;
        double dist = BlockGeometry.reach(p, d, Box.block(clicked.getX(), clicked.getY(), clicked.getZ()));
        if (dist > limit) return flag(p, 1.0 + (dist - limit) * 4.0, "reach=" + Util.fmt(dist));
        reward(p, 0.97);
        return false;
    }
}
