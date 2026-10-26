package net.h2020.watchdog.punish;

import net.h2020.watchdog.WatchdogServiceImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Random;

/**
 * The "[WATCHDOG ANNOUNCEMENT]" broadcast every 10-15 minutes. Base numbers look like a real summer-2020 week
 * (6-7k Watchdog bans, ~3k staff bans), drift upward slowly while the server runs, and every real ban adds one.
 */
public final class Announcer {
    private final WatchdogServiceImpl service;
    private final Random random = new Random();
    private double watchdogBase, staffBase;
    private long lastDrift;
    private BukkitTask task;

    public Announcer(WatchdogServiceImpl service) {
        this.service = service;
        this.watchdogBase = 5900 + random.nextInt(1400);
        this.staffBase = 2600 + random.nextInt(800);
        this.lastDrift = System.currentTimeMillis();
    }

    public void start() { scheduleNext(); }

    private void scheduleNext() {
        int min = service.config().announceMinMinutes, max = service.config().announceMaxMinutes;
        long ticks = (min * 60L + random.nextInt(Math.max(1, (max - min) * 60 + 1))) * 20L;
        task = Bukkit.getScheduler().runTaskLater(service.plugin(), () -> {
            if (!Bukkit.getOnlinePlayers().isEmpty()) announce();
            scheduleNext();
        }, ticks);
    }

    private void drift() {
        long now = System.currentTimeMillis();
        double minutes = (now - lastDrift) / 60000.0;
        lastDrift = now;
        // ~900 Watchdog bans/day on the network, ~430 staff bans/day; with noise and the 7-day window rolling.
        watchdogBase += minutes * (0.45 + random.nextDouble() * 0.5) - minutes * 0.25 * random.nextDouble();
        staffBase += minutes * (0.2 + random.nextDouble() * 0.25) - minutes * 0.12 * random.nextDouble();
    }

    public int watchdogCount() {
        drift();
        return (int) watchdogBase + service.bans().bansSinceBoot();
    }

    public int staffCount() {
        drift();
        return (int) staffBase;
    }

    public void announce() {
        String[] lines = {
                "§4[WATCHDOG ANNOUNCEMENT]",
                "§fWatchdog has banned §c§l" + String.format("%,d", watchdogCount()) + " §fplayers in the last 7 days.",
                "§fStaff have banned an additional §c§l" + String.format("%,d", staffCount()) + " §fin the last 7 days.",
                "§c§lBlacklisted modifications are a bannable offense!"
        };
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(lines);
        service.log("ANNOUNCE", lines[1] + " " + lines[2]);
    }

    public void stop() {
        if (task != null) task.cancel();
    }
}
