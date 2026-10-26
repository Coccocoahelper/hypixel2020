package net.h2020.core;

import io.netty.channel.Channel;
import net.h2020.api.H2020;
import net.h2020.api.bot.BotService;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.IChatBaseComponent;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PlayerConnection;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

/** Small NMS / bot helpers shared inside core. */
public final class CoreUtil {
    private CoreUtil() {}

    /** True for server-side bots (BotService if loaded, else "no real Netty channel"). */
    public static boolean isBot(Player p) {
        if (p == null) return false;
        BotService bs = H2020.find(BotService.class);
        if (bs != null) {
            try { if (bs.isBot(p)) return true; } catch (Throwable ignored) {}
        }
        return realChannel(p) == null;
    }

    /** The real Netty channel of a human client, or null for bots / disconnected players. */
    public static Channel realChannel(Player p) {
        if (!(p instanceof CraftPlayer)) return null;
        EntityPlayer ep = ((CraftPlayer) p).getHandle();
        PlayerConnection pc = ep.playerConnection;
        if (pc == null || pc.networkManager == null) return null;
        Channel ch = pc.networkManager.channel;
        if (ch == null || ch.pipeline() == null) return null;
        // fake/embedded channels used by bots have no packet_handler in their pipeline
        if (ch.pipeline().get("packet_handler") == null) return null;
        return ch;
    }

    public static void send(Player p, Packet<?> packet) {
        if (!(p instanceof CraftPlayer)) return;
        PlayerConnection pc = ((CraftPlayer) p).getHandle().playerConnection;
        if (pc != null) pc.sendPacket(packet);
    }

    public static IChatBaseComponent text(String legacy) {
        return IChatBaseComponent.ChatSerializer.a("{\"text\":\"" + escapeJson(legacy) + "\"}");
    }

    public static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
