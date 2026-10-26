package net.h2020.watchdog.framework;

import net.h2020.api.packet.PacketHub;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayInArmAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayInEntityAction;
import net.minecraft.server.v1_8_R3.PacketPlayInFlying;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityVelocity;
import net.minecraft.server.v1_8_R3.PacketPlayOutPosition;
import net.minecraft.server.v1_8_R3.PacketPlayOutRespawn;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * The single PacketHub listener of Watchdog. Keeps the shared per-player packet state (rotations, positions,
 * per-tick swing/attack counters, velocity/teleport times) and fans packets out to the check groups in a fixed order.
 */
public final class PacketRouter implements PacketHub.Listener {
    private final WatchdogServiceImpl service;
    private final List<PacketCheck> checks = new CopyOnWriteArrayList<PacketCheck>();
    private volatile long lastErrorLog;

    public PacketRouter(WatchdogServiceImpl service) { this.service = service; }

    public void add(PacketCheck c) { checks.add(c); }

    @Override
    public boolean onInbound(Player p, Packet<?> packet) {
        if (p == null || packet == null) return true;
        PlayerData d = service.data(p);
        long now = System.currentTimeMillis();
        boolean pass = true;
        synchronized (d) {
            d.lastPacketMs = now;
            if (packet instanceof PacketPlayInFlying) {
                PacketPlayInFlying f = (PacketPlayInFlying) packet;
                d.onFlying(f.g(), f.a(), f.b(), f.c(), f.h(), f.d(), f.e(), f.f(), now);
                for (PacketCheck c : checks) {
                    try { if (!c.onFlying(p, d, f, now)) pass = false; } catch (Throwable t) { error(t); }
                }
                d.resetTick();
                if (d.skipRotationSamples > 0) d.skipRotationSamples--;
                return pass;
            }
            if (packet instanceof PacketPlayInArmAnimation) {
                d.swingsThisTick++;
                d.swingConsumed = false;
            } else if (packet instanceof PacketPlayInEntityAction) {
                PacketPlayInEntityAction a = (PacketPlayInEntityAction) packet;
                switch (a.b()) {
                    case START_SPRINTING: d.sprinting = true; break;
                    case STOP_SPRINTING: d.sprinting = false; break;
                    case START_SNEAKING: d.sneaking = true; break;
                    case STOP_SNEAKING: d.sneaking = false; break;
                    default: break;
                }
            }
            for (PacketCheck c : checks) {
                try { if (!c.onPacket(p, d, packet, now)) pass = false; } catch (Throwable t) { error(t); }
            }
        }
        return pass;
    }

    @Override
    public void onOutbound(Player p, Packet<?> packet) {
        if (p == null || packet == null) return;
        PlayerData d = service.dataIfPresent(p.getUniqueId());
        if (d == null) return;
        long now = System.currentTimeMillis();
        synchronized (d) {
            if (packet instanceof PacketPlayOutEntityVelocity) {
                if (Reflect.velocityEntity((PacketPlayOutEntityVelocity) packet) == p.getEntityId()) d.lastVelocityMs = now;
            } else if (packet instanceof PacketPlayOutPosition || packet instanceof PacketPlayOutRespawn) {
                d.onTeleport(now);
            }
            for (PacketCheck c : checks) {
                try { c.onOutbound(p, d, packet, now); } catch (Throwable t) { error(t); }
            }
        }
    }

    private void error(Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog > 10000) {
            lastErrorLog = now;
            service.plugin().getLogger().log(Level.WARNING, "[Watchdog] check error", t);
        }
    }
}
