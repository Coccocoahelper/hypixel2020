package net.h2020.watchdog.checks.blockplace;

import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.BlockGeometry;
import net.h2020.watchdog.framework.PacketCheck;
import net.h2020.watchdog.framework.PacketRouter;
import net.h2020.watchdog.framework.PlayerData;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.ItemBlock;
import net.minecraft.server.v1_8_R3.ItemStack;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayInArmAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockPlace;
import net.minecraft.server.v1_8_R3.PacketPlayInFlying;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** BlockPlace check group (NCP BlockPlaceListener): FastPlace, Reach, Direction, Against, NoSwing, Scaffold. */
public final class BlockPlaceChecks implements Listener, PacketCheck {
    private final WatchdogServiceImpl service;
    private final FastPlace fastPlace;
    private final Reach reach;
    private final Direction direction;
    private final Against against;
    private final NoSwing noSwing;
    private final Scaffold scaffold;

    private BlockPlaceChecks(WatchdogServiceImpl s) {
        this.service = s;
        fastPlace = new FastPlace(s);
        reach = new Reach(s);
        direction = new Direction(s);
        against = new Against(s);
        noSwing = new NoSwing(s);
        scaffold = new Scaffold(s);
    }

    public static void register(WatchdogServiceImpl service, PacketRouter router, JavaPlugin plugin) {
        BlockPlaceChecks c = new BlockPlaceChecks(service);
        router.add(c);
        service.registerListener(c);
    }

    // ------------------------------------------------------------------ packets

    @Override
    public boolean onPacket(Player p, PlayerData d, Packet<?> packet, long now) {
        if (packet instanceof PacketPlayInBlockPlace) {
            PacketPlayInBlockPlace bp = (PacketPlayInBlockPlace) packet;
            int face = bp.getFace();
            ItemStack item = bp.getItemStack();
            if (face == 255 || face < 0 || face > 5 || item == null || !(item.getItem() instanceof ItemBlock)) return true;
            BlockPosition pos = bp.a();
            BlockPlaceData pd = d.place;
            pd.lastX = pos.getX();
            pd.lastY = pos.getY();
            pd.lastZ = pos.getZ();
            pd.lastFace = face;
            pd.lastPacketMs = now;
            d.placesThisTick++;
            noSwing.onPlacePacket(d);
            fastPlace.onPacket(p, d, now);
            BlockFace f = BlockGeometry.face(face);
            scaffold.onPlacePacket(d, pos.getX() + f.getModX(), pos.getY() + f.getModY(), pos.getZ() + f.getModZ());
            return true;
        }
        if (packet instanceof PacketPlayInArmAnimation) {
            if (noSwing.onSwing(d)) d.swingConsumed = true;
        }
        return true;
    }

    @Override
    public boolean onFlying(Player p, PlayerData d, PacketPlayInFlying f, long now) {
        noSwing.onTickEnd(p, d);
        scaffold.onTickEnd(p, d, now);
        return true;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        Block placed = e.getBlockPlaced();
        Block clicked = e.getBlockAgainst();
        PlayerData d = service.data(p);
        long now = System.currentTimeMillis();
        boolean cancel = false;
        synchronized (d) {
            BlockPlaceData pd = d.place;
            BlockFace face;
            boolean replaced = clicked.getX() == placed.getX() && clicked.getY() == placed.getY() && clicked.getZ() == placed.getZ();
            if (!replaced) face = clicked.getFace(placed);
            else if (pd.lastX == clicked.getX() && pd.lastY == clicked.getY() && pd.lastZ == clicked.getZ()) face = BlockGeometry.face(pd.lastFace);
            else face = null;
            if (face == BlockFace.SELF) face = null;

            if (now < pd.cancelPlaceUntil) cancel = true;
            if (against.check(p, e)) cancel = true;
            if (reach.check(p, d, clicked)) cancel = true;
            if (direction.check(p, d, clicked, face)) cancel = true;
            if (scaffold.check(p, d, placed, now)) cancel = true;
            noSwing.onPlaced(p, d, clicked.getX(), clicked.getY(), clicked.getZ());
        }
        if (cancel) e.setCancelled(true);
    }
}
