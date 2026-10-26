package net.h2020.watchdog.checks.blockplace;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import org.bukkit.entity.Player;

/**
 * NCP BlockPlace.FastPlace: block placements (C08 on a face while holding a block) per second over a 1s window
 * (limit 20) and a short-term limit of 10 within 10 client ticks (vanilla right-click delay is 4 ticks; jitter
 * clicking reaches ~1/tick). Measured on packet arrival so server lag cannot fake bursts; a cancel marks the
 * following BlockPlaceEvent(s) for cancellation (the server then resends the block, no ghost blocks).
 */
public final class FastPlace extends Check {
    static final float LIMIT = 20f;
    static final int SHORT_TERM_TICKS = 10;
    static final int SHORT_TERM_LIMIT = 10;

    public FastPlace(WatchdogServiceImpl service) { super(service, CheckType.BLOCKPLACE_FASTPLACE); }

    /** Packet thread, on a block-placing C08. */
    public void onPacket(Player p, PlayerData d, long now) {
        if (exempt(p)) return;
        BlockPlaceData pd = d.place;
        pd.fastPlaceBuckets.add(now, 1f);
        float full = pd.fastPlaceBuckets.score(1f);
        if (d.flyingCount - pd.shortTermStart < SHORT_TERM_TICKS) pd.shortTermCount++;
        else { pd.shortTermStart = d.flyingCount; pd.shortTermCount = 1; }
        double violation = Math.max(full - LIMIT, pd.shortTermCount - SHORT_TERM_LIMIT);
        if (violation > 0 && flag(p, violation, "bps=" + (int) full + " short=" + pd.shortTermCount + "/" + SHORT_TERM_TICKS + "t")) {
            pd.cancelPlaceUntil = now + 150;
        }
    }
}
