package net.h2020.watchdog.checks.moving;

import net.h2020.api.watchdog.CheckType;
import org.bukkit.entity.Player;

/**
 * NCP moving.morepackets (the classic buffer variant): the buffer starts at 50, every movement packet costs 1 and
 * every full second refills 22, capped at 100 (so up to ~4.5 s of lag can be caught up at once). After a freeze
 * of more than 4 s the buffer is raised to cover the whole gap, because a vanilla client then legitimately sends
 * everything it buffered. A negative buffer is a violation (vl = packets over the limit); on cancel the player is
 * set back to the last legit position. Teleport confirmations are free. Runs on the packet thread.
 */
final class MorePackets {
    static final double BUFFER_START = 50;
    static final double BUFFER_MAX = 100;
    static final double PER_SECOND = 22;

    private MorePackets() {}

    static void onFlying(MovingEngine eng, Player p, MovingData d, double now, boolean confirm) {
        if (confirm) return;
        if (d.mpLast < 0) {
            d.mpLast = now;
            d.mpLastPacket = now;
            d.mpBuffer = BUFFER_START;
        }
        double gap = now - d.mpLastPacket;
        d.mpLastPacket = now;
        if (gap > 4000) d.mpBuffer = Math.max(d.mpBuffer, gap / 50.0 + 10);
        d.mpBuffer -= 1;
        if (now - d.mpLast >= 1000) {
            int secs = (int) ((now - d.mpLast) / 1000);
            if (d.mpBuffer < BUFFER_MAX) d.mpBuffer = Math.min(BUFFER_MAX, d.mpBuffer + PER_SECOND * secs);
            d.mpLast += secs * 1000.0;
        }
        if (d.mpBuffer < 0) {
            if (now - d.mpLastFlag >= 500) {
                d.mpLastFlag = now;
                final int over = (int) Math.ceil(-d.mpBuffer);
                eng.flagAsync(p, CheckType.MOVING_MOREPACKETS, over,
                        "morepackets: " + over + " movement packets over the limit (" + (int) PER_SECOND + "/s)", true);
            }
            if (d.mpBuffer < -40) d.mpBuffer = -40;
        }
    }

    static void reset(MovingData d) {
        d.mpLast = -1;
        d.mpLastPacket = -1;
        d.mpBuffer = BUFFER_START;
    }
}
