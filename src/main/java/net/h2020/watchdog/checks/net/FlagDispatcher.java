package net.h2020.watchdog.checks.net;

import net.h2020.api.watchdog.CheckType;
import net.h2020.api.watchdog.WatchdogService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Hands violations found on packet threads to the main thread, where the WatchdogService is called once per tick.
 */
public final class FlagDispatcher {
    /** Run on the main thread when the framework says the violation should be cancelled / set back. */
    public interface OnCancel { void run(Player p); }

    private static final class Pending {
        final Player player; final CheckType check; final double vl; final String details; final OnCancel onCancel;
        Pending(Player player, CheckType check, double vl, String details, OnCancel onCancel) {
            this.player = player; this.check = check; this.vl = vl; this.details = details; this.onCancel = onCancel;
        }
    }

    private final WatchdogService service;
    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();

    public FlagDispatcher(WatchdogService service) { this.service = service; }

    public WatchdogService service() { return service; }

    public void flag(Player p, CheckType check, double vl, String details, OnCancel onCancel) {
        // Always deferred to the tick task, even on the main thread: a bot's packets are dispatched from inside
        // its own packet handling, where an immediate kick/ban must not happen.
        queue.add(new Pending(p, check, vl, details, onCancel));
    }

    /** Main thread, every tick. */
    public void drain() {
        Pending x;
        int n = 0;
        while (n++ < 4096 && (x = queue.poll()) != null) dispatch(x);
    }

    public void clear() { queue.clear(); }

    private void dispatch(Pending x) {
        try {
            if (!x.player.isOnline()) return;
            if (service.isExempt(x.player, x.check)) return;
            boolean cancel = service.flag(x.player, x.check, x.vl, x.details);
            if (cancel && x.onCancel != null) x.onCancel.run(x.player);
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[Watchdog] flag dispatch failed for " + x.check + ": " + t);
        }
    }
}
