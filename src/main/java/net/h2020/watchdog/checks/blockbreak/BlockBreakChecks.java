package net.h2020.watchdog.checks.blockbreak;

import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.PacketCheck;
import net.h2020.watchdog.framework.PacketRouter;
import net.h2020.watchdog.framework.PlayerData;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayInArmAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockDig;
import net.minecraft.server.v1_8_R3.PacketPlayInFlying;
import net.minecraft.server.v1_8_R3.PacketPlayInUseEntity;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** BlockBreak check group (NCP BlockBreakListener): FastBreak, Reach, Direction, NoSwing. */
public final class BlockBreakChecks implements Listener, PacketCheck {
    private final WatchdogServiceImpl service;
    private final FastBreak fastBreak;
    private final Reach reach;
    private final Direction direction;
    private final NoSwing noSwing;

    private BlockBreakChecks(WatchdogServiceImpl s) {
        this.service = s;
        fastBreak = new FastBreak(s);
        reach = new Reach(s);
        direction = new Direction(s);
        noSwing = new NoSwing(s);
    }

    public static void register(WatchdogServiceImpl service, PacketRouter router, JavaPlugin plugin) {
        BlockBreakChecks c = new BlockBreakChecks(service);
        router.add(c);
        service.registerListener(c);
    }

    // ------------------------------------------------------------------ packets

    @Override
    public boolean onPacket(Player p, PlayerData d, Packet<?> packet, long now) {
        BlockBreakData bd = d.dig;
        if (packet instanceof PacketPlayInBlockDig) {
            PacketPlayInBlockDig dig = (PacketPlayInBlockDig) packet;
            BlockPosition pos = dig.a();
            switch (dig.c()) {
                case START_DESTROY_BLOCK:
                    // 1.8: C0A precedes C07 START in the same tick
                    bd.start(pos.getX(), pos.getY(), pos.getZ(), now, d.flyingCount, d.swingsThisTick);
                    break;
                case ABORT_DESTROY_BLOCK:
                    bd.digging = false;
                    break;
                case STOP_DESTROY_BLOCK: {
                    bd.digging = false;
                    BlockBreakData.Dig r = bd.find(pos.getX(), pos.getY(), pos.getZ());
                    if (r != null && r.stopMs == 0) r.stopMs = now;
                    break;
                }
                default:
                    break;
            }
            return true;
        }
        if (packet instanceof PacketPlayInUseEntity
                && ((PacketPlayInUseEntity) packet).a() == PacketPlayInUseEntity.EnumEntityUseAction.ATTACK) {
            bd.digging = false; // the crosshair is on an entity now
            return true;
        }
        if (packet instanceof PacketPlayInArmAnimation) {
            noSwing.onSwing(d);
            BlockBreakData.Dig cur = bd.current();
            if (cur != null && (bd.digging || cur.startTick == d.flyingCount)) {
                cur.swings++;
                d.swingConsumed = true;
            }
        }
        return true;
    }

    @Override
    public boolean onFlying(Player p, PlayerData d, PacketPlayInFlying f, long now) {
        noSwing.onTickEnd(p, d);
        return true;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        Block b = e.getBlock();
        PlayerData d = service.data(p);
        boolean cancel = false;
        synchronized (d) {
            BlockBreakData.Dig dig = d.dig.find(b.getX(), b.getY(), b.getZ());
            boolean fresh = dig != null && !dig.judged;
            if (fresh) dig.judged = true;
            boolean instant = fresh && dig.stopMs == 0;
            if (instant && d.dig.current() == dig) d.dig.digging = false; // no STOP follows an instant break
            if (fresh && !instant && p.getGameMode() != GameMode.CREATIVE) {
                if (fastBreak.check(p, d, b, dig.stopMs - dig.startMs)) cancel = true;
            }
            if (reach.check(p, d, b)) cancel = true;
            if (direction.check(p, d, b)) cancel = true;
            if (fresh && noSwing.onBreak(p, d, dig, instant)) cancel = true;
        }
        if (cancel) e.setCancelled(true);
    }
}
