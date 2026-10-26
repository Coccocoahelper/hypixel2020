package net.h2020.watchdog.checks.net;

import net.h2020.api.watchdog.CheckType;
import org.bukkit.entity.Player;

import java.util.Iterator;

/**
 * Keep-alive integrity. The vanilla 1.8 client answers S00 on its network thread immediately and in order, so:
 * - an id the server never sent is forged ("ping spoof"/disabler);
 * - answering a newer keep-alive while older ones stay unanswered is out of order;
 * - holding a keep-alive back while 80+ flying packets (4 s of play) keep arriving is a delayed response.
 * All checks only arm after the client answered at least once, so a client that never answers (e.g. a bot
 * implementation without keep-alive support) is left to the server's own timeout.
 */
final class KeepAliveCheck {
    static final int MAX_PENDING = 40;
    static final int MAX_HISTORY = 80;
    static final long DELAY_PACKETS = 80;
    static final long DELAY_MS = 4000;

    private KeepAliveCheck() {}

    static void onOutbound(NetData d, int id) {
        synchronized (d) {
            d.kaPending.addLast(new long[]{id, System.currentTimeMillis(), d.flyingCount, 0});
            while (d.kaPending.size() > MAX_PENDING) d.kaPending.removeFirst();
            d.kaHistory.add(id);
            if (d.kaHistory.size() > MAX_HISTORY) {
                Iterator<Integer> it = d.kaHistory.iterator();
                it.next();
                it.remove();
            }
        }
    }

    static void onInbound(NetChecks nc, Player p, NetData d, int id) {
        String violation = null;
        double vl = 0;
        synchronized (d) {
            if (d.kaHistory.isEmpty()) return; // outbound not observed for this connection
            if (!d.kaHistory.contains(id)) {
                if (d.kaAnswered) { violation = "keepalive: unknown id " + id; vl = 2; }
            } else {
                int skipped = 0;
                boolean found = false;
                for (long[] e : d.kaPending) {
                    if ((int) e[0] == id) { found = true; break; }
                    skipped++;
                }
                if (found) {
                    for (int i = 0; i <= skipped; i++) d.kaPending.removeFirst();
                    if (skipped > 0 && d.kaAnswered) { violation = "keepalive: out of order (skipped " + skipped + ")"; vl = 1; }
                    d.kaAnswered = true;
                } else if (d.kaAnswered) {
                    violation = "keepalive: duplicate response " + id;
                    vl = 1;
                }
            }
        }
        if (violation != null) nc.flag(p, CheckType.NET_KEEPALIVE, vl, violation, false);
    }

    static void onFlying(NetChecks nc, Player p, NetData d) {
        String violation = null;
        synchronized (d) {
            d.flyingCount++;
            if (!d.kaAnswered) return;
            long[] head = d.kaPending.peekFirst();
            if (head != null && head[3] == 0 && d.flyingCount - head[2] > DELAY_PACKETS
                    && System.currentTimeMillis() - head[1] > DELAY_MS) {
                head[3] = 1;
                violation = "keepalive: response held back for " + (d.flyingCount - head[2]) + " movement packets";
            }
        }
        if (violation != null) nc.flag(p, CheckType.NET_KEEPALIVE, 2, violation, false);
    }
}
