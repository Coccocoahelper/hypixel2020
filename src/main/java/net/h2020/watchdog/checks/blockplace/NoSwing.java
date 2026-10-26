package net.h2020.watchdog.checks.blockplace;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import org.bukkit.entity.Player;

/**
 * BlockPlace.NoSwing: a successful 1.8 placement makes the client swing (C08 then C0A in the same tick). Failed
 * placements do not swing, so only placements the server confirmed (BlockPlaceEvent) are judged, at whichever comes
 * last: the end of the C08's client tick or the event.
 */
public final class NoSwing extends Check {
    public NoSwing(WatchdogServiceImpl service) { super(service, CheckType.BLOCKPLACE_NOSWING); }

    /** Packet thread: block-placing C08. */
    public void onPlacePacket(PlayerData d) {
        BlockPlaceData pd = d.place;
        pd.placeTick = d.flyingCount;
        pd.placeSwung = false;
        pd.placeConfirmed = false;
    }

    /** Packet thread: C0A. @return true if this swing belongs to a placement */
    public boolean onSwing(PlayerData d) {
        BlockPlaceData pd = d.place;
        if (pd.placeTick == d.flyingCount && !pd.placeSwung) {
            pd.placeSwung = true;
            return true;
        }
        return false;
    }

    /** Main thread: the server placed the block clicked at (x,y,z). Ignored unless it matches the latest C08. */
    public void onPlaced(Player p, PlayerData d, int x, int y, int z) {
        BlockPlaceData pd = d.place;
        if (pd.lastX != x || pd.lastY != y || pd.lastZ != z) return;
        pd.placeConfirmed = true;
        if (pd.placeTick < d.flyingCount && !pd.placeSwung) judge(p, pd);
    }

    /** Packet thread: the client tick ended (flyingCount already advanced). */
    public void onTickEnd(Player p, PlayerData d) {
        BlockPlaceData pd = d.place;
        if (pd.placeTick == d.flyingCount - 1 && pd.placeConfirmed && !pd.placeSwung) judge(p, pd);
    }

    private void judge(Player p, BlockPlaceData pd) {
        pd.placeSwung = true; // judged once
        if (!exempt(p)) flag(p, 1.0, "placed without swing");
    }
}
