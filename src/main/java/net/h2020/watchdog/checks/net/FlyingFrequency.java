package net.h2020.watchdog.checks.net;

import net.h2020.api.watchdog.CheckType;
import org.bukkit.entity.Player;

/**
 * NCP net.flyingfrequency: more than 60 flying packets per second averaged over 5 seconds. A vanilla client sends
 * 20/s; even the catch-up after a 10 s freeze stays far below the limit. Buckets are reset after a gap of more than
 * 3 seconds so a catch-up burst is judged on its own.
 */
final class FlyingFrequency {
    static final int BUCKETS = 5;
    static final double BUCKET_MS = 1000.0;
    static final int PER_SECOND = 60;

    private FlyingFrequency() {}

    static void onFlying(NetChecks nc, Player p, NetData d, double now) {
        if (d.freqLastPacket >= 0 && now - d.freqLastPacket > 3000) {
            for (int i = 0; i < BUCKETS; i++) { d.freqCount[i] = 0; d.freqBucketId[i] = 0; }
        }
        d.freqLastPacket = now;
        long bucket = (long) Math.floor(now / BUCKET_MS);
        int idx = (int) Math.floorMod(bucket, (long) BUCKETS);
        if (d.freqBucketId[idx] != bucket) {
            d.freqBucketId[idx] = bucket;
            d.freqCount[idx] = 0;
        }
        d.freqCount[idx]++;

        int total = 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (bucket - d.freqBucketId[i] < BUCKETS) total += d.freqCount[i];
        }
        int limit = PER_SECOND * BUCKETS;
        if (total > limit && now - d.freqLastFlag > 1000) {
            d.freqLastFlag = now;
            nc.flag(p, CheckType.NET_FLYINGFREQUENCY, Math.min(20.0, (total - limit) / 10.0),
                    "flyingfrequency: " + total + " packets in " + BUCKETS + "s (max " + limit + ")", false);
        }
    }
}
