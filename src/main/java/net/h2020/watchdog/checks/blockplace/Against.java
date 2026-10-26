package net.h2020.watchdog.checks.blockplace;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.Util;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * NCP BlockPlace.Against: the client ray trace can only click solid targets. Placing against air ("airplace"
 * scaffolds) or liquid (except lily pads) is impossible. The 1.8 server places directly into a clicked replaceable
 * block, so "against == placed" means the clicked block itself was replaced: its previous type is what was clicked.
 * Latency (a block broken a moment ago) can cause a rare legit case, so VL decays fast and cancelling starts at vl>2.
 */
public final class Against extends Check {
    public Against(WatchdogServiceImpl service) { super(service, CheckType.BLOCKPLACE_AGAINST); }

    public boolean check(Player p, BlockPlaceEvent e) {
        if (exempt(p)) return false;
        Block placed = e.getBlockPlaced(), against = e.getBlockAgainst();
        Material clicked;
        if (against.getX() == placed.getX() && against.getY() == placed.getY() && against.getZ() == placed.getZ()) {
            clicked = e.getBlockReplacedState() == null ? Material.AIR : e.getBlockReplacedState().getType();
        } else {
            clicked = against.getType();
        }
        Material item = placed.getType();
        if (clicked == Material.AIR) return flag(p, 1.0, "against=air");
        if (Util.isLiquid(clicked) && item != Material.WATER_LILY) return flag(p, 1.0, "against=" + clicked.name().toLowerCase());
        return false;
    }
}
