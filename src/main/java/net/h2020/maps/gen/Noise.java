package net.h2020.maps.gen;

/** Seeded, deterministic 2D/3D value noise (smoothstep interpolated) with fractal octaves. Range about [-1, 1]. */
public final class Noise {
    private final long seed;

    public Noise(long seed) { this.seed = seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L; }

    private double hash(int x, int y, int z) {
        long h = seed;
        h ^= x * 0x27D4EB2FL; h *= 0x165667B1L;
        h ^= y * 0x1B873593L; h *= 0x85EBCA6BL;
        h ^= z * 0xC2B2AE35L; h *= 0x27D4EB2F165667C5L;
        h ^= (h >>> 31);
        return ((h >>> 11) & 0xFFFFFFL) / (double) 0xFFFFFF * 2.0 - 1.0;
    }

    private static double fade(double t) { return t * t * (3 - 2 * t); }

    public double value(double x, double z) { return value(x, 0, z); }

    public double value(double x, double y, double z) {
        int x0 = floor(x), y0 = floor(y), z0 = floor(z);
        double fx = fade(x - x0), fy = fade(y - y0), fz = fade(z - z0);
        double c000 = hash(x0, y0, z0), c100 = hash(x0 + 1, y0, z0), c010 = hash(x0, y0 + 1, z0), c110 = hash(x0 + 1, y0 + 1, z0);
        double c001 = hash(x0, y0, z0 + 1), c101 = hash(x0 + 1, y0, z0 + 1), c011 = hash(x0, y0 + 1, z0 + 1), c111 = hash(x0 + 1, y0 + 1, z0 + 1);
        double x00 = lerp(c000, c100, fx), x10 = lerp(c010, c110, fx), x01 = lerp(c001, c101, fx), x11 = lerp(c011, c111, fx);
        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz);
    }

    /** Fractal sum, normalised to about [-1, 1]. */
    public double fractal(double x, double z, double scale, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1.0 / scale;
        for (int i = 0; i < octaves; i++) {
            sum += value(x * f + i * 17.3, z * f - i * 9.1) * amp;
            norm += amp; amp *= 0.5; f *= 2;
        }
        return sum / norm;
    }

    /** Per-position deterministic random in [0,1). */
    public double rand(int x, int y, int z) { return (hash(x, y, z) + 1) * 0.5; }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static int floor(double v) { int i = (int) v; return v < i ? i - 1 : i; }
}
