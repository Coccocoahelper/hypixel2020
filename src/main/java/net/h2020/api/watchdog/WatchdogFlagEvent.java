package net.h2020.api.watchdog;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired (main thread, async-safe dispatch by the framework) whenever a check adds violation level. */
public class WatchdogFlagEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final CheckType check;
    private final double totalVl;
    private final String details;

    public WatchdogFlagEvent(Player player, CheckType check, double totalVl, String details) {
        this.player = player; this.check = check; this.totalVl = totalVl; this.details = details;
    }

    public Player player() { return player; }
    public CheckType check() { return check; }
    public double totalVl() { return totalVl; }
    public String details() { return details; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
