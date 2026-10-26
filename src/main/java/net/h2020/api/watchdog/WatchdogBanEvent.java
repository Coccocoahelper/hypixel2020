package net.h2020.api.watchdog;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the main thread right before a player is banned/removed. Games use it to eliminate the player. */
public class WatchdogBanEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String reasonCode;

    public WatchdogBanEvent(Player player, String reasonCode) { this.player = player; this.reasonCode = reasonCode; }

    public Player player() { return player; }
    public String reasonCode() { return reasonCode; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
