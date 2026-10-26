package net.h2020.watchdog.framework;

import net.h2020.api.H2020;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotService;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

/** Small helpers shared by checks. */
public final class Util {
    private Util() {}

    public static final String ADMIN_PERMISSION = "h2020.admin";

    /** Effective latency in ms: the NMS keep-alive ping, or the simulated ping of a bot if higher. */
    public static int ping(Player p) {
        int ping = 0;
        try { ping = ((CraftPlayer) p).getHandle().ping; } catch (Throwable ignored) {}
        BotService bs = H2020.find(BotService.class);
        if (bs != null) {
            try {
                Bot b = bs.bot(p);
                if (b != null && b.profile() != null) ping = Math.max(ping, b.profile().pingMs);
            } catch (Throwable ignored) {}
        }
        return Math.max(0, Math.min(ping, 1000));
    }

    public static boolean isBot(Player p) {
        BotService bs = H2020.find(BotService.class);
        if (bs == null) return false;
        try { return bs.isBot(p); } catch (Throwable t) { return false; }
    }

    public static Bot bot(Player p) {
        BotService bs = H2020.find(BotService.class);
        if (bs == null) return null;
        try { return bs.bot(p); } catch (Throwable t) { return null; }
    }

    public static boolean isAdmin(Player p) {
        return p != null && !isBot(p) && (p.isOp() || p.hasPermission(ADMIN_PERMISSION));
    }

    /** 1.8 eye height: 1.62, minus 0.08 while sneaking. */
    public static double eyeHeight(boolean sneaking) { return sneaking ? 1.54 : 1.62; }

    public static boolean creativeLike(Player p) {
        GameMode g = p.getGameMode();
        return g == GameMode.CREATIVE || g == GameMode.SPECTATOR;
    }

    public static boolean isLiquid(Material m) {
        return m == Material.WATER || m == Material.STATIONARY_WATER || m == Material.LAVA || m == Material.STATIONARY_LAVA;
    }

    public static boolean isClimbable(Material m) { return m == Material.LADDER || m == Material.VINE; }

    /** Feet or head in liquid / web / on ladder: vanilla physics and crit rules differ there. */
    public static boolean inSpecialMedium(Player p) {
        Block feet = p.getLocation().getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Material f = feet.getType(), h = head.getType();
        return isLiquid(f) || isLiquid(h) || isClimbable(f) || f == Material.WEB || h == Material.WEB;
    }

    public static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    public static String fmt1(double d) {
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }
}
