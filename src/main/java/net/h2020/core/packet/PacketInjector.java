package net.h2020.core.packet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.h2020.api.packet.PacketHub;
import net.h2020.core.CoreUtil;
import net.minecraft.server.v1_8_R3.Packet;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.logging.Logger;

/**
 * Netty injection for real clients: a duplex handler placed right before "packet_handler" (the NetworkManager)
 * routes every serverbound packet through PacketHub.dispatchInbound (dropped when it returns false) and every
 * clientbound packet through PacketHub.dispatchOutbound. Bots have no real channel and dispatch themselves.
 */
public final class PacketInjector implements Listener {
    public static final String HANDLER = "h2020_packets";
    private final Logger log;

    public PacketInjector(Logger log) { this.log = log; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) { inject(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { uninject(e.getPlayer()); }

    public void injectAll() { for (Player p : Bukkit.getOnlinePlayers()) inject(p); }

    public void uninjectAll() { for (Player p : Bukkit.getOnlinePlayers()) uninject(p); }

    public void inject(final Player player) {
        final Channel ch = CoreUtil.realChannel(player);
        if (ch == null) return; // bot or already disconnected
        ch.eventLoop().execute(new Runnable() {
            @Override public void run() {
                try {
                    if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER);
                    if (ch.pipeline().get("packet_handler") == null) return;
                    ch.pipeline().addBefore("packet_handler", HANDLER, new Handler(player));
                } catch (Throwable t) {
                    log.warning("Packet injection failed for " + player.getName() + ": " + t);
                }
            }
        });
    }

    public void uninject(Player player) {
        final Channel ch = CoreUtil.realChannel(player);
        if (ch == null) return;
        ch.eventLoop().execute(new Runnable() {
            @Override public void run() {
                try { if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER); } catch (Throwable ignored) {}
            }
        });
    }

    /** One per connection; holds the Bukkit Player it belongs to. */
    private static final class Handler extends ChannelDuplexHandler {
        private final Player player;

        Handler(Player player) { this.player = player; }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Packet) {
                boolean pass;
                try { pass = PacketHub.dispatchInbound(player, (Packet<?>) msg); }
                catch (Throwable t) { pass = true; }
                if (!pass) return; // dropped: never reaches the PlayerConnection
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof Packet) {
                try { PacketHub.dispatchOutbound(player, (Packet<?>) msg); } catch (Throwable ignored) {}
            }
            super.write(ctx, msg, promise);
        }
    }
}
