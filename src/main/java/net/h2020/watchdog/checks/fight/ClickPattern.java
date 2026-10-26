package net.h2020.watchdog.checks.fight;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.Geometry;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.entity.Player;

/**
 * Autoclicker heuristics on combat swings (C0A not caused by digging/placing, within 2s of an attack):
 * <ul>
 * <li>sustained CPS above 20 for two consecutive seconds;</li>
 * <li>too-consistent intervals: stdDev below 10ms over 20 clicks at 7+ CPS (1.8 clicks are tick-sampled, so a human's
 *   intervals spread over several ticks);</li>
 * <li>a strictly periodic tick pattern (period 1-4) over 30 intervals that is not constant;</li>
 * <li>3+ clicks inside one client tick, repeatedly (beyond butterfly double clicks).</li>
 * </ul>
 * Thresholds are conservative so human-model bots with natural variance pass.
 */
public final class ClickPattern extends Check {
    static final int CPS_LIMIT = 20;
    static final int ANALYSE_EVERY = 20;
    static final long BREAK_MS = 500;

    public ClickPattern(WatchdogServiceImpl service) { super(service, CheckType.FIGHT_CLICKPATTERN); }

    /** Packet thread, on a combat swing. */
    public void onClick(Player p, PlayerData d, long now) {
        FightData fd = d.fight;
        if (now - d.lastAttackMs > 2000) return;
        // per-second CPS windows
        if (now - fd.cpsWindowStart >= 1000) {
            if (fd.cpsWindowStart != 0 && now - fd.cpsWindowStart < 2000) {
                if (fd.cpsWindowCount > CPS_LIMIT) {
                    fd.cpsStreak++;
                    if (fd.cpsStreak >= 2 && !exempt(p)) flag(p, fd.cpsWindowCount - CPS_LIMIT, "cps=" + fd.cpsWindowCount);
                } else fd.cpsStreak = 0;
            } else fd.cpsStreak = 0;
            fd.cpsWindowStart = now;
            fd.cpsWindowCount = 0;
        }
        fd.cpsWindowCount++;

        // interval ring; a long pause breaks the sequence
        if (fd.clickCount > 0) {
            int last = (fd.clickHead - 1 + FightData.CLICKS) % FightData.CLICKS;
            if (now - fd.clickMs[last] > BREAK_MS) { fd.clickCount = 0; fd.clicksSinceAnalysis = 0; fd.multiClickTicks = 0; }
        }
        fd.clickMs[fd.clickHead] = now;
        fd.clickTick[fd.clickHead] = d.flyingCount;
        fd.clickHead = (fd.clickHead + 1) % FightData.CLICKS;
        if (fd.clickCount < FightData.CLICKS) fd.clickCount++;
        if (d.swingsThisTick == 3) fd.multiClickTicks++;
        if (++fd.clicksSinceAnalysis >= ANALYSE_EVERY && fd.clickCount > ANALYSE_EVERY) {
            fd.clicksSinceAnalysis = 0;
            analyse(p, fd);
        }
    }

    private void analyse(Player p, FightData fd) {
        // last 20 ms-intervals
        int n = ANALYSE_EVERY;
        double[] iv = new double[n];
        for (int i = 0; i < n; i++) {
            int a = (fd.clickHead - 1 - i + FightData.CLICKS) % FightData.CLICKS;
            int b = (fd.clickHead - 2 - i + FightData.CLICKS) % FightData.CLICKS;
            iv[i] = fd.clickMs[a] - fd.clickMs[b];
        }
        double mean = Geometry.mean(iv, n), sd = Geometry.stdDev(iv, n);
        int multi = fd.multiClickTicks;
        fd.multiClickTicks = 0;
        if (exempt(p)) return;
        String base = "cps=" + Util.fmt1(mean > 0 ? 1000.0 / mean : 0) + " sd=" + Util.fmt1(sd) + "ms";
        if (mean < 143 && sd < 10) {
            flag(p, 4.0, "consistent " + base);
            return;
        }
        // tick periodicity over up to 30 tick-intervals
        int m = Math.min(30, fd.clickCount - 1);
        if (m >= 30) {
            long[] t = new long[m];
            for (int i = 0; i < m; i++) {
                int a = (fd.clickHead - 1 - i + FightData.CLICKS) % FightData.CLICKS;
                int b = (fd.clickHead - 2 - i + FightData.CLICKS) % FightData.CLICKS;
                t[i] = fd.clickTick[a] - fd.clickTick[b];
            }
            boolean constant = true;
            for (int i = 1; i < m; i++) if (t[i] != t[0]) { constant = false; break; }
            if (!constant) {
                for (int period = 2; period <= 4; period++) {
                    boolean periodic = true;
                    for (int i = period; i < m; i++) if (t[i] != t[i - period]) { periodic = false; break; }
                    if (periodic) {
                        flag(p, 4.0, "periodic period=" + period + " " + base);
                        return;
                    }
                }
            }
        }
        if (multi >= 3) {
            flag(p, 3.0, "multiclick ticks=" + multi + " " + base);
            return;
        }
        if (debugging(p)) debug(p, "ok " + base + " multi=" + multi);
    }
}
