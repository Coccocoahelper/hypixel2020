package net.h2020.watchdog.checks.net;

import org.bukkit.Bukkit;

/**
 * Time source for packet-rate checks.
 * Real players' packets are delivered on Netty threads, so wall-clock time measures what the client sends.
 * Bot virtual clients deliver on the main thread once per server tick; for them "time" is the server tick
 * counter (50 ms per tick), so server lag / Spigot tick catch-up never looks like a timer cheat.
 */
public final class PacketClock {
    private static volatile long ticks;

    private PacketClock() {}

    /** Called once per server tick by the moving tick task. */
    public static void tick() { ticks++; }

    public static long serverTicks() { return ticks; }

    /** Milliseconds on the clock appropriate for the calling thread. */
    public static double now(boolean mainThread) {
        return mainThread ? ticks * 50.0 : System.nanoTime() / 1_000_000.0;
    }

    /** Selects the clock for this player's packet thread, resetting timing state if the thread kind changed. */
    public static double now(NetData d) {
        boolean main = Bukkit.isPrimaryThread();
        if (d.mainThreadClock == null || d.mainThreadClock != main) {
            d.mainThreadClock = main;
            d.resetTiming();
        }
        return now(main);
    }
}
