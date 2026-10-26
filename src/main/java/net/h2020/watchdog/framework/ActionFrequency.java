package net.h2020.watchdog.framework;

/**
 * NCP's ActionFrequency: a fixed number of time buckets that shift as time passes. score(factor) sums the buckets,
 * weighting older buckets by factor^i. Not thread-safe; guard with the owning PlayerData.
 */
public final class ActionFrequency {
    private final float[] buckets;
    private final long durBucket;
    /** Start time of bucket 0. */
    private long time;

    public ActionFrequency(int nBuckets, long durBucket) {
        this.buckets = new float[nBuckets];
        this.durBucket = durBucket;
    }

    public void add(long now, float amount) {
        update(now);
        buckets[0] += amount;
    }

    public void update(long now) {
        long diff = now - time;
        if (diff < 0 || diff >= durBucket * buckets.length) {
            clear(now);
            return;
        }
        int shift = (int) (diff / durBucket);
        if (shift == 0) return;
        for (int i = buckets.length - 1; i >= shift; i--) buckets[i] = buckets[i - shift];
        for (int i = 0; i < shift; i++) buckets[i] = 0f;
        time += shift * durBucket;
    }

    public void clear(long now) {
        for (int i = 0; i < buckets.length; i++) buckets[i] = 0f;
        time = now;
    }

    public float score(float factor) {
        float s = 0f, f = 1f;
        for (float b : buckets) { s += b * f; f *= factor; }
        return s;
    }

    public float bucketScore(int i) { return buckets[i]; }

    public int numberOfBuckets() { return buckets.length; }

    public long bucketDuration() { return durBucket; }

    /** Total covered time span in ms. */
    public long span() { return durBucket * buckets.length; }
}
