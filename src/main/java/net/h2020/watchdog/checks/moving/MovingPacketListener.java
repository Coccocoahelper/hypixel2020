package net.h2020.watchdog.checks.moving;

import net.h2020.api.packet.PacketHub;
import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.checks.net.PacketClock;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.IBlockData;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayInAbilities;
import net.minecraft.server.v1_8_R3.PacketPlayInFlying;
import net.minecraft.server.v1_8_R3.PacketPlayInKeepAlive;
import net.minecraft.server.v1_8_R3.PacketPlayInUseEntity;
import net.minecraft.server.v1_8_R3.PacketPlayOutBlockChange;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityVelocity;
import net.minecraft.server.v1_8_R3.PacketPlayOutExplosion;
import net.minecraft.server.v1_8_R3.PacketPlayOutKeepAlive;
import net.minecraft.server.v1_8_R3.PacketPlayOutMultiBlockChange;
import net.minecraft.server.v1_8_R3.PacketPlayOutPosition;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;

/**
 * PacketHub listener. Inbound: snapshots every flying packet into the player's queue (teleport confirmation and
 * ignore state decided here, in packet order), runs the packet-rate checks. Outbound: teleports (S08), own
 * velocity (S12), explosions (S27), keep-alives (S00) and block changes (S23/S22) near the player.
 * Thread-safe: runs on Netty threads for humans and on the main thread for bots. Never drops packets.
 */
final class MovingPacketListener implements PacketHub.Listener {
    private static final double GHOST_RANGE_SQ = 6.0 * 6.0;

    private final MovingEngine eng;

    MovingPacketListener(MovingEngine eng) { this.eng = eng; }

    private static long teleportTimeout(MovingData d) {
        return Math.min(8000L, 3000L + 3L * d.pingSnapshot);
    }

    @Override
    public boolean onInbound(Player p, Packet<?> packet) {
        MovingData d = eng.get(p);
        if (d == null) return true;
        if (packet instanceof PacketPlayInFlying) {
            PacketPlayInFlying f = (PacketPlayInFlying) packet;
            long now = System.currentTimeMillis();
            long seq = d.inSeq + 1;
            d.inSeq = seq;
            boolean hasPos = f.g();
            boolean confirm = false, ignored = false;
            if (hasPos && d.teleports.confirm(f.a(), f.b(), f.c())) confirm = true;
            else if (d.teleports.hasPending(now, teleportTimeout(d))) ignored = true;
            FlyingEntry e = new FlyingEntry(seq, now, f.a(), f.b(), f.c(), f.d(), f.e(), f.f(), hasPos, f.h(), ignored, confirm);
            d.queue.add(e);
            if (d.queueSize.incrementAndGet() > 1600) {
                d.queue.poll();
                d.queueSize.decrementAndGet();
            }
            double clock = PacketClock.now(d.net);
            eng.net.onFlying(p, d.net, clock, confirm);
            if (eng.config.enabled(CheckType.MOVING_MOREPACKETS)) MorePackets.onFlying(eng, p, d, clock, confirm);
            return true;
        }
        if (packet instanceof PacketPlayInAbilities) {
            if (((PacketPlayInAbilities) packet).isFlying()) d.lastClientFlyClaim = System.currentTimeMillis();
        } else if (packet instanceof PacketPlayInUseEntity) {
            if (((PacketPlayInUseEntity) packet).a() == PacketPlayInUseEntity.EnumEntityUseAction.ATTACK) d.recordAttack();
        }
        // Keep-alive replies may arrive on a different thread than the rest of the stream (bots answer them on a
        // timer thread, like vanilla's network thread). They do not use the clock, and selecting it here would flip
        // the clock domain and reset Timer/MorePackets state every keep-alive.
        double clock = packet instanceof PacketPlayInKeepAlive ? 0.0 : PacketClock.now(d.net);
        eng.net.onInbound(p, d.net, packet, clock);
        return true;
    }

    @Override
    public void onOutbound(Player p, Packet<?> packet) {
        MovingData d = eng.get(p);
        if (d == null) return;
        if (packet instanceof PacketPlayOutPosition) {
            onPosition(p, d, (PacketPlayOutPosition) packet);
        } else if (packet instanceof PacketPlayOutEntityVelocity) {
            PacketPlayOutEntityVelocity v = (PacketPlayOutEntityVelocity) packet;
            if (PacketFields.velocityEntity(v) == p.getEntityId()) {
                d.velocity.add(false, PacketFields.velX(v), PacketFields.velY(v), PacketFields.velZ(v), d.inSeq);
            }
        } else if (packet instanceof PacketPlayOutExplosion) {
            PacketPlayOutExplosion x = (PacketPlayOutExplosion) packet;
            double kx = PacketFields.explosionX(x), ky = PacketFields.explosionY(x), kz = PacketFields.explosionZ(x);
            if (Math.abs(kx) + Math.abs(ky) + Math.abs(kz) > 1.0E-4) d.velocity.add(true, kx, ky, kz, d.inSeq);
            List<BlockPosition> blocks = PacketFields.explosionBlocks(x);
            if (!blocks.isEmpty()) {
                EntityPlayer h = handle(p);
                long expiry = expiry(d);
                for (BlockPosition b : blocks) {
                    if (h != null && near(h, b)) d.ghosts.addSolid(b.getX(), b.getY(), b.getZ(), expiry);
                }
            }
        } else if (packet instanceof PacketPlayOutKeepAlive) {
            eng.net.onKeepAliveOut(d.net, PacketFields.keepAliveId((PacketPlayOutKeepAlive) packet));
        } else if (packet instanceof PacketPlayOutBlockChange) {
            PacketPlayOutBlockChange bc = (PacketPlayOutBlockChange) packet;
            BlockPosition pos = PacketFields.blockChangePos(bc);
            if (pos != null) ghost(p, d, pos, bc.block);
        } else if (packet instanceof PacketPlayOutMultiBlockChange) {
            PacketPlayOutMultiBlockChange.MultiBlockChangeInfo[] infos =
                    PacketFields.multiBlockInfos((PacketPlayOutMultiBlockChange) packet);
            if (infos != null) {
                for (PacketPlayOutMultiBlockChange.MultiBlockChangeInfo i : infos) {
                    if (i != null) ghost(p, d, i.a(), i.c());
                }
            }
        }
    }

    private void onPosition(Player p, MovingData d, PacketPlayOutPosition pp) {
        double x = PacketFields.posX(pp), y = PacketFields.posY(pp), z = PacketFields.posZ(pp);
        Set<? extends Enum<?>> flags = PacketFields.posFlags(pp);
        if (!flags.isEmpty()) {
            EntityPlayer h = handle(p);
            if (h == null) return;
            for (Enum<?> f : flags) {
                String n = f.name();
                if ("X".equals(n)) x += h.locX;
                else if ("Y".equals(n)) y += h.locY;
                else if ("Z".equals(n)) z += h.locZ;
            }
        }
        d.teleports.add(x, y, z);
    }

    private void ghost(Player p, MovingData d, BlockPosition pos, IBlockData data) {
        EntityPlayer h = handle(p);
        if (h == null || !near(h, pos)) return;
        boolean solid = data != null && data.getBlock().getMaterial().isSolid();
        long expiry = expiry(d);
        // Became solid: the client may still walk through it (passable) - and, once it has it, stand on it even if
        // the server reverted it again. Became passable: the client may still stand on the old block.
        if (solid) d.ghosts.addAir(pos.getX(), pos.getY(), pos.getZ(), expiry);
        d.ghosts.addSolid(pos.getX(), pos.getY(), pos.getZ(), expiry);
    }

    private static long expiry(MovingData d) {
        return System.currentTimeMillis() + 300L + d.pingSnapshot * 3L / 2L;
    }

    private static boolean near(EntityPlayer h, BlockPosition b) {
        double dx = b.getX() + 0.5 - h.locX, dy = b.getY() + 0.5 - (h.locY + 0.9), dz = b.getZ() + 0.5 - h.locZ;
        return dx * dx + dy * dy + dz * dz <= GHOST_RANGE_SQ;
    }

    private static EntityPlayer handle(Player p) {
        try {
            return ((CraftPlayer) p).getHandle();
        } catch (Throwable t) {
            return null;
        }
    }
}
