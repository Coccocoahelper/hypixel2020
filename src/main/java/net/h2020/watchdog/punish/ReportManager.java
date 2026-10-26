package net.h2020.watchdog.punish;

import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.PlayerData;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /report and /wdr. A report boosts the priority of the reported player's checks (violations count more for a
 * while, alerts are tagged) and the reporter is thanked when Watchdog bans that player.
 */
public final class ReportManager {
    private final WatchdogServiceImpl service;
    /** reporter|target -> last report time */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<String, Long>();

    public ReportManager(WatchdogServiceImpl service) { this.service = service; }

    public void report(Player reporter, Player target, String reason) {
        if (reporter.equals(target)) {
            reporter.sendMessage(ChatColor.RED + "You cannot report yourself!");
            return;
        }
        long now = System.currentTimeMillis();
        String key = reporter.getUniqueId() + "|" + target.getUniqueId();
        Long last = cooldowns.get(key);
        if (last != null && now - last < service.config().reportCooldownSeconds * 1000L) {
            reporter.sendMessage(ChatColor.RED + "You have already reported this player recently!");
            return;
        }
        cooldowns.put(key, now);
        String category = categorize(reason);
        PlayerData d = service.data(target);
        d.reporters.add(reporter.getUniqueId());
        if ("Cheating".equals(category)) d.reportedUntil = now + service.config().reportBoostMinutes * 60000L;

        reporter.sendMessage(ChatColor.GREEN + "Thanks for your " + category + " report. We understand your concerns and it "
                + "will be reviewed as soon as possible.");
        service.alerts().broadcast(ChatColor.RED + "[WD] " + ChatColor.GRAY + reporter.getName() + ChatColor.WHITE + " reported "
                + ChatColor.GRAY + target.getName() + ChatColor.DARK_GRAY + " (" + (reason.isEmpty() ? "cheating" : reason) + ")"
                + ChatColor.GOLD + " x" + d.reporters.size());
        service.log("REPORT", reporter.getName() + " -> " + target.getName() + " [" + category + "] " + reason);
    }

    private static String categorize(String reason) {
        String r = reason.toLowerCase();
        if (r.contains("chat") || r.contains("spam") || r.contains("toxic") || r.contains("swear") || r.contains("advert")) return "Chat";
        return "Cheating";
    }
}
