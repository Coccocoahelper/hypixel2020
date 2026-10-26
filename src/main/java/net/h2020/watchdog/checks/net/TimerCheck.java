package net.h2020.watchdog.checks.net;

import net.h2020.api.watchdog.CheckType;
import org.bukkit.entity.Player;

/**
 * Watchdog "Timer": every client tick produces exactly one flying packet, so over time the client may not send
 * more than one packet per 50 ms. A balance accumulates (50 ms - elapsed) per packet:
 * - negative balance (lag, FPS drops, network freezes) is banked down to a floor of 1 s plus the largest gap seen
 *   in the last 10 s, so the burst that follows a lag spike is absorbed exactly;
 * - positive balance bleeds 0.5 ms per packet so a delayed first packet (baseline bias) cannot false flag;
 * - a balance above 150 ms (3 surplus ticks) is a violation. Detects timers of roughly 1.05x and above.
 * Teleport confirmations are sent outside the client tick and are not counted.
 */
final class TimerCheck {
    static final double TICK_MS = 50.0;
    static final double THRESHOLD_MS = 150.0;
    static final double BLEED_MS = 0.5;
    static final double FLOOR_MS = 1000.0;
    static final int SETTLE_PACKETS = 40;

    private TimerCheck() {}

    static void onFlying(NetChecks nc, Player p, NetData d, double now, boolean teleportConfirm) {
        if (teleportConfirm) return;
        if (d.timerLast < 0) { d.timerLast = now; return; }
        double elapsed = now - d.timerLast;
        d.timerLast = now;
        if (elapsed < 0) elapsed = 0;

        if (elapsed >= d.timerMaxGap || now - d.timerMaxGapAt > 10_000) {
            d.timerMaxGap = elapsed;
            d.timerMaxGapAt = now;
        }
        d.timerBalance += TICK_MS - elapsed;
        double floor = -(FLOOR_MS + d.timerMaxGap);
        if (d.timerBalance < floor) d.timerBalance = floor;
        if (d.timerBalance > 0) d.timerBalance = Math.max(0, d.timerBalance - BLEED_MS);

        if (++d.timerPackets < SETTLE_PACKETS) {
            // Join / world load: let the connection settle before trusting the baseline.
            if (d.timerBalance > 0) d.timerBalance = 0;
            return;
        }
        if (d.timerBalance > THRESHOLD_MS) {
            double extra = d.timerBalance / TICK_MS;
            nc.flag(p, CheckType.NET_TIMER, Math.min(10.0, extra),
                    String.format("timer: balance +%.0fms (%.1f ticks ahead)", d.timerBalance, extra), true);
            d.timerBalance = 0;
        }
    }
}
