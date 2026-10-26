package net.h2020.watchdog.checks.inventory;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.entity.Player;

/**
 * NCP Inventory.Open, as far as 1.8 makes it detectable: with a GUI open the vanilla client releases all movement
 * keys and cannot click in the world. Violations: attacking with a window open, starting to sprint with a window
 * open, sustained (non-decaying) walking speed with a window open, and inventory-manager clicks in the own inventory
 * without having opened it while fighting/sprinting. Cancel = drop the attack / close the window.
 */
public final class Open extends Check {
    static final long GRACE_MS = 300;

    public Open(WatchdogServiceImpl service) { super(service, CheckType.INVENTORY_OPEN); }

    /** Packet thread, C02 attack. @return cancel (drop) */
    public boolean onAttack(Player p, PlayerData d, long now) {
        InventoryData id = d.inv;
        if (!id.anyOpen() || now - id.openMs < GRACE_MS || exempt(p)) return false;
        return flag(p, 1.0, "attack with " + (id.containerOpen ? "container" : "inventory") + " open");
    }

    /** Packet thread, C0B START_SPRINTING. */
    public void onSprint(Player p, PlayerData d, long now) {
        InventoryData id = d.inv;
        if (!id.anyOpen() || now - id.openMs < GRACE_MS || exempt(p)) return;
        flag(p, 0.5, "sprint with inventory open");
    }

    /** Packet thread, C0E in window 0 (own inventory). */
    public void onOwnInventoryClick(Player p, PlayerData d, long now) {
        InventoryData id = d.inv;
        if (id.invOpen || exempt(p) || Util.creativeLike(p)) return;
        if (d.sprinting || now - d.lastAttackMs < 500) {
            flag(p, 1.0, "inventory click without open" + (d.sprinting ? " (sprinting)" : " (fighting)"));
        }
    }

    /** Packet thread, end of client tick. @return true if the window should be closed */
    public boolean onTickEnd(Player p, PlayerData d, long now) {
        InventoryData id = d.inv;
        if (!id.anyOpen() || now - id.openMs < 400 || !d.onGround || d.recentlyTookVelocity(now, 1500)
                || now - d.lastTeleportMs < 1000) {
            id.moveWhileOpenTicks = 0;
            return false;
        }
        if (d.deltaXZ > 0.12 && d.deltaXZ >= d.prevDeltaXZ * 0.98) id.moveWhileOpenTicks++;
        else id.moveWhileOpenTicks = Math.max(0, id.moveWhileOpenTicks - 2);
        if (id.moveWhileOpenTicks <= 10 || exempt(p)) return false;
        id.moveWhileOpenTicks = 0;
        return flag(p, 1.0, "moving with inventory open speed=" + Util.fmt(d.deltaXZ));
    }
}
