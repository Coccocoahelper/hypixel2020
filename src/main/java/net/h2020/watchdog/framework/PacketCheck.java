package net.h2020.watchdog.framework;

import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayInFlying;
import org.bukkit.entity.Player;

/**
 * Packet-level part of a check group. Called by the PacketRouter with the player's PlayerData locked.
 * Netty thread for humans, main thread for bots: never touch the world from here (schedule instead).
 */
public interface PacketCheck {
    /**
     * A flying packet (C03-C06) ends the client tick. Called after PlayerData was updated with the new
     * position/rotation, while the per-tick counters (swings/attacks/places) still describe the finished tick.
     * @return false to drop the packet
     */
    default boolean onFlying(Player p, PlayerData d, PacketPlayInFlying f, long now) { return true; }

    /** Any other serverbound packet. @return false to drop it. */
    default boolean onPacket(Player p, PlayerData d, Packet<?> packet, long now) { return true; }

    /** Clientbound packet to this player (velocity, windows, teleports...). */
    default void onOutbound(Player p, PlayerData d, Packet<?> packet, long now) {}
}
