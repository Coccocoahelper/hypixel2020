package net.h2020.watchdog.framework;

/** Vector math shared by the checks (pure functions, thread-safe). Angles in degrees, Minecraft yaw convention. */
public final class Geometry {
    private Geometry() {}

    /** Look vector for a yaw/pitch, exactly as vanilla Entity.getLook (f(pitch, yaw)). */
    public static double[] direction(float yaw, float pitch) {
        double f = Math.cos(-yaw * 0.017453292 - Math.PI);
        double f1 = Math.sin(-yaw * 0.017453292 - Math.PI);
        double f2 = -Math.cos(-pitch * 0.017453292);
        double f3 = Math.sin(-pitch * 0.017453292);
        return new double[] {f1 * f2, f3, f * f2};
    }

    /** Euclidean distance from a point to the closest point of a box (0 if inside). */
    public static double distanceToBox(double x, double y, double z, Box b) {
        double dx = Math.max(Math.max(b.minX - x, 0), x - b.maxX);
        double dy = Math.max(Math.max(b.minY - y, 0), y - b.maxY);
        double dz = Math.max(Math.max(b.minZ - z, 0), z - b.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Ray/box intersection (slab method). Returns entry distance t >= 0, or -1 if the ray misses within maxT. */
    public static double rayIntersect(double ox, double oy, double oz, double dx, double dy, double dz, Box b, double maxT) {
        double tmin = 0, tmax = maxT;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz};
        double[] mn = {b.minX, b.minY, b.minZ}, mx = {b.maxX, b.maxY, b.maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (o[i] < mn[i] || o[i] > mx[i]) return -1;
            } else {
                double t1 = (mn[i] - o[i]) / d[i], t2 = (mx[i] - o[i]) / d[i];
                if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) return -1;
            }
        }
        return tmin;
    }

    /**
     * How far (in blocks) the look ray passes beside the box: 0 if it hits within maxT, otherwise the smallest
     * distance between any ray point (0..maxT) and the box. The distance-to-convex-set along a line is convex, so a
     * ternary search finds the minimum.
     */
    public static double rayOffset(double ox, double oy, double oz, double dx, double dy, double dz, Box b, double maxT) {
        if (rayIntersect(ox, oy, oz, dx, dy, dz, b, maxT) >= 0) return 0;
        double lo = 0, hi = maxT;
        for (int i = 0; i < 48; i++) {
            double m1 = lo + (hi - lo) / 3, m2 = hi - (hi - lo) / 3;
            double f1 = distanceToBox(ox + dx * m1, oy + dy * m1, oz + dz * m1, b);
            double f2 = distanceToBox(ox + dx * m2, oy + dy * m2, oz + dz * m2, b);
            if (f1 > f2) lo = m1; else hi = m2;
        }
        double t = (lo + hi) * 0.5;
        return distanceToBox(ox + dx * t, oy + dy * t, oz + dz * t, b);
    }

    /** Angle in degrees between the look direction and the vector from the eye to a point. */
    public static double angleTo(double ex, double ey, double ez, double[] look, double px, double py, double pz) {
        double vx = px - ex, vy = py - ey, vz = pz - ez;
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 1e-6) return 0;
        double dot = (vx * look[0] + vy * look[1] + vz * look[2]) / len;
        dot = Math.max(-1, Math.min(1, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    /** Yaw (degrees) needed to look from (x,z) at (tx,tz). */
    public static float yawTo(double x, double z, double tx, double tz) {
        return (float) (Math.toDegrees(Math.atan2(tz - z, tx - x)) - 90.0);
    }

    /** Absolute wrapped yaw difference in [0,180]. */
    public static float yawDiff(float a, float b) {
        float d = (a - b) % 360f;
        if (d < 0) d += 360f;
        if (d > 180f) d = 360f - d;
        return d;
    }

    public static double mean(double[] v, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) s += v[i];
        return n == 0 ? 0 : s / n;
    }

    public static double stdDev(double[] v, int n) {
        if (n < 2) return 0;
        double m = mean(v, n), s = 0;
        for (int i = 0; i < n; i++) s += (v[i] - m) * (v[i] - m);
        return Math.sqrt(s / (n - 1));
    }

    /** Euclid on fixed-point rotation deltas; values below 2^14 are treated as float noise (stops recursion). */
    public static long gcd(long a, long b) {
        while (b > 16384L) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /** Fixed-point expander used for rotation GCD analysis (2^24). */
    public static final double GCD_EXPANDER = 16777216.0;
}
