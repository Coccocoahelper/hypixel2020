package net.h2020.watchdog.framework;

/**
 * Server tick counter and lag estimate (NCP TickTask equivalent). run() is called once per tick on the main thread;
 * everything else is safe from any thread.
 */
public final class TickTask implements Runnable {
    private static volatile int tick;
    private static volatile long lastTickMs;
    private static volatile long lagSpikeUntil;
    private static final long[] durations = new long[40];
    private static volatile double tps = 20.0;

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        long last = lastTickMs;
        if (last != 0) {
            long dur = now - last;
            synchronized (durations) {
                durations[tick % durations.length] = dur;
                long sum = 0;
                int n = 0;
                for (long d : durations) if (d > 0) { sum += d; n++; }
                if (n > 0) tps = Math.min(20.0, 1000.0 / Math.max(1.0, (double) sum / n));
            }
            // A single tick of 150ms+ means packets were queued and will be processed in a burst.
            if (dur > 150) lagSpikeUntil = now + 2000 + dur;
        }
        lastTickMs = now;
        tick++;
    }

    public static int tick() { return tick; }

    public static double tps() { return tps; }

    /** True while recovering from a lag spike (main-thread timing checks should be lenient). */
    public static boolean lagging() { return System.currentTimeMillis() < lagSpikeUntil || tps < 17.5; }

    /** NCP getLag(duration): factor >= 1 by which main-thread-measured durations are stretched. */
    public static float lagFactor() { return (float) Math.max(1.0, 20.0 / Math.max(1.0, tps)); }

    public static void reset() {
        tick = 0;
        lastTickMs = 0;
        lagSpikeUntil = 0;
        tps = 20.0;
        synchronized (durations) { for (int i = 0; i < durations.length; i++) durations[i] = 0; }
    }
}
