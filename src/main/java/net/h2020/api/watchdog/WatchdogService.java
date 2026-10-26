package net.h2020.api.watchdog;

import org.bukkit.entity.Player;

/**
 * Owned by the watchdog-combat builder (framework). Checks call flag(); the framework keeps NCP-style violation
 * levels (decaying), runs configured actions (log, setback/cancel already decided by the check, staff alert,
 * kick/ban at thresholds) and fires WatchdogFlagEvent / WatchdogBanEvent.
 */
public interface WatchdogService {
    /**
     * Add violation level. Returns true if the check SHOULD cancel/setback (vl above the check's cancel threshold
     * and the player is not exempt).
     */
    boolean flag(Player p, CheckType check, double addVl, String details);
    double violationLevel(Player p, CheckType check);
    /** Exempt a player from a check (or whole group via any check of it when group=true) for durationMs. */
    void exempt(Player p, CheckType check, long durationMs);
    boolean isExempt(Player p, CheckType check);
    /** Ban flow: Hypixel ban screen for real players; bots are removed with the in-game
     *  "A player has been removed from your game." message and the reporter thanks. */
    void ban(Player p, String reasonCode);
    /** Total watchdog bans since boot, used in the "[WATCHDOG ANNOUNCEMENT]" broadcast. */
    int bansLast7Days();
}
