package net.h2020.watchdog.alerts;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.framework.Util;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staff alerts: "§c[WD] §7Name §ffailed §cFIGHT_REACH §7(vl 12.3) §8reach=3.47" to online h2020.admins. With
 * staff-alerts on, every admin gets them until /wd alerts turns them off; otherwise only admins who turned them on.
 * Safe to call from any thread (sends on the main thread).
 */
public final class AlertManager {
    public static final String PREFIX = ChatColor.RED + "[WD] ";

    private final JavaPlugin plugin;
    /** Players whose setting differs from the default (opted out when defaultOn, opted in otherwise). */
    private final Set<UUID> toggled = ConcurrentHashMap.newKeySet();
    private final boolean defaultOn;
    private volatile boolean console;

    public AlertManager(JavaPlugin plugin, boolean console, boolean defaultOn) {
        this.plugin = plugin;
        this.console = console;
        this.defaultOn = defaultOn;
    }

    /** @return true if alerts are now enabled for the player */
    public boolean toggle(Player p) {
        if (!toggled.remove(p.getUniqueId())) toggled.add(p.getUniqueId());
        return enabled(p);
    }

    public boolean enabled(Player p) { return defaultOn != toggled.contains(p.getUniqueId()); }

    public boolean console() { return console; }

    public void flagAlert(String name, CheckType check, double vl, String details, boolean reported) {
        StringBuilder sb = new StringBuilder();
        sb.append(PREFIX).append(ChatColor.GRAY).append(name).append(' ')
          .append(ChatColor.WHITE).append("failed ")
          .append(ChatColor.RED).append(check.name()).append(' ')
          .append(ChatColor.GRAY).append("(vl ").append(Util.fmt1(vl)).append(')');
        if (details != null && !details.isEmpty()) sb.append(' ').append(ChatColor.DARK_GRAY).append(details);
        if (reported) sb.append(' ').append(ChatColor.GOLD).append("[reported]");
        broadcast(sb.toString());
    }

    /** Raw staff message (ban notices etc.). */
    public void broadcast(final String message) {
        Runnable r = () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (Util.isAdmin(p) && enabled(p)) p.sendMessage(message);
            }
            if (console) plugin.getLogger().info(ChatColor.stripColor(message));
        };
        if (Bukkit.isPrimaryThread()) r.run();
        else {
            try { Bukkit.getScheduler().runTask(plugin, r); } catch (Exception ignored) {} // plugin disabling
        }
    }
}
